# Seat Reservation Service — Implementation Plan

## Stack
- **Language/Framework**: Java 21 + Spring Boot 3.x
- **Database**: PostgreSQL 15
- **Connection Pool**: HikariCP (Spring default)
- **Metrics**: Micrometer + Prometheus (spring-boot-actuator)
- **Auth**: Simple JWT (JJWT library) — token carries `user_id`
- **Containerization**: Dockerfile + docker-compose.yml
- **Deployment**: User-managed (Dockerfile provided)

## Project Layout

```
seat-reservation/
├── src/main/java/com/seatreservation/
│   ├── SeatReservationApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java          # JWT filter chain
│   │   └── MetricsConfig.java           # Custom Prometheus counters/gauges
│   ├── controller/
│   │   ├── ShowController.java          # POST /shows, GET /shows/{id}
│   │   ├── ReservationController.java   # POST /shows/{id}/reserve
│   │   └── HealthController.java        # /health/live, /health/ready
│   ├── service/
│   │   ├── ShowService.java
│   │   └── ReservationService.java      # Core atomic logic
│   ├── repository/
│   │   ├── ShowRepository.java
│   │   ├── SeatRepository.java
│   │   └── ReservationRepository.java
│   ├── model/
│   │   ├── Show.java
│   │   ├── Seat.java                    # seat_status enum: AVAILABLE/HELD/CONFIRMED
│   │   └── Reservation.java
│   ├── dto/
│   │   ├── CreateShowRequest.java
│   │   ├── ReserveRequest.java
│   │   └── (response DTOs)
│   └── exception/
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/                    # Flyway migrations
│       ├── V1__create_shows.sql
│       ├── V2__create_seats.sql
│       └── V3__create_reservations.sql
├── Dockerfile
├── docker-compose.yml
├── burst.sh
├── README.md
└── WRITEUP.md
```

## Database Schema

### shows
```sql
CREATE TABLE shows (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          TEXT NOT NULL,
  price_paise   BIGINT NOT NULL CHECK (price_paise >= 0),
  per_user_limit INT NOT NULL DEFAULT 4,
  created_at    TIMESTAMPTZ DEFAULT now()
);
```

### seats
```sql
CREATE TABLE seats (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  show_id  UUID NOT NULL REFERENCES shows(id),
  label    TEXT NOT NULL,
  status   TEXT NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | CONFIRMED
  version  BIGINT NOT NULL DEFAULT 0,           -- optimistic lock fallback
  UNIQUE (show_id, label)
);
CREATE INDEX idx_seats_show_status ON seats(show_id, status);
```

### reservations
```sql
CREATE TABLE reservations (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  show_id          UUID NOT NULL REFERENCES shows(id),
  user_id          TEXT NOT NULL,
  idempotency_key  TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'CONFIRMED',  -- CONFIRMED | CANCELLED
  amount_paise     BIGINT NOT NULL,
  created_at       TIMESTAMPTZ DEFAULT now(),
  UNIQUE (show_id, idempotency_key)   -- enforces exactly-once per show
);
```

### reservation_seats (join table)
```sql
CREATE TABLE reservation_seats (
  reservation_id UUID NOT NULL REFERENCES reservations(id),
  seat_id        UUID NOT NULL REFERENCES seats(id),
  PRIMARY KEY (reservation_id, seat_id)
);
```

## Atomic Decision Mechanism

**The core race-free primitive**: `SELECT ... FOR UPDATE SKIP LOCKED` + single transaction.

For a reserve request:
1. Begin transaction (SERIALIZABLE or READ COMMITTED with explicit locks)
2. Lock the requested seat rows in **deterministic label order** (prevents deadlock for multi-seat):
   ```sql
   SELECT * FROM seats
   WHERE show_id = ? AND label IN (?, ?)
   ORDER BY label          -- deterministic order → no deadlock
   FOR UPDATE              -- exclusive row lock
   ```
3. Inside the lock, check:
   - All seats are AVAILABLE → proceed
   - Any seat is HELD/CONFIRMED → rollback, return 409 "seat-taken"
4. Check idempotency key (unique constraint on `(show_id, idempotency_key)`):
   - Key exists with same seats → return existing reservation (200)
   - Key exists with different seats → 409 "key-conflict"
5. Check per-user seat count:
   ```sql
   SELECT COUNT(*) FROM reservation_seats rs
   JOIN reservations r ON r.id = rs.reservation_id
   WHERE r.show_id = ? AND r.user_id = ? AND r.status = 'CONFIRMED'
   ```
   If count + requested > per_user_limit → 409 "per-user-limit"
6. UPDATE seats SET status = 'CONFIRMED' WHERE id IN (...)
7. INSERT INTO reservations + reservation_seats
8. COMMIT

**Why this is race-free**: The `FOR UPDATE` lock on seat rows means only one transaction can hold the lock at a time. The second transaction blocks until the first commits, then sees the updated status and returns 409. No read-then-write gap.

**Deadlock prevention**: All multi-seat requests lock seats in alphabetical label order. Two concurrent requests for [A12, A13] both try to lock A12 first, then A13 — no circular wait.

**Idempotency enforcement**: The `UNIQUE (show_id, idempotency_key)` constraint is the final backstop. Even if two identical requests race through all checks simultaneously, only one INSERT succeeds; the other gets a unique-constraint violation which we catch and convert to a replay response.

## API Endpoints

### POST /shows
- Admin-only (token with role=ADMIN)
- Creates show + all seats in AVAILABLE state
- Returns full show object with seats

### POST /shows/{id}/reserve
- Authenticated user (JWT)
- Body: `{ "seats": ["A12"], "idempotency_key": "..." }`
- All-or-nothing: if any seat unavailable → 409
- Returns 201 on success, 409 on decline (with `reason` field)

### POST /reservations/{id}/cancel
- Owner only (token user_id must match reservation user_id)
- Sets reservation status = CANCELLED
- Sets seat status back to AVAILABLE
- Atomic: wrapped in transaction

### GET /shows/{id}
- Public
- Returns show metadata + per-seat status + counts
- Invariant: available + held + confirmed == total_seats

### GET /health/live
- Always 200 if app is running

### GET /health/ready
- 200 if DB connection pool can execute a simple query
- 503 if DB is unreachable

### GET /actuator/prometheus
- Prometheus metrics endpoint

## Metrics

Custom Micrometer metrics:
- `reservations_confirmed_total` (counter, tags: show_id)
- `reservations_declined_total` (counter, tags: show_id, reason=[seat_taken|per_user_limit|idempotent_replay|key_conflict])
- `seats_available` (gauge, tags: show_id) — queried from DB
- `reservation_duration_seconds` (histogram) — latency of reserve endpoint

## Auth Model

Simple JWT:
- `POST /auth/token` — takes `{ "user_id": "u1", "role": "USER" }` → returns JWT
- JWT payload: `{ "sub": "u1", "role": "USER" }` signed with HS256
- Filter extracts user_id from token — body fields for user_id are ignored
- Admin endpoints require `role=ADMIN`

## Burst Script (burst.sh)

```bash
#!/usr/bin/env bash
# Usage: ./burst.sh <BASE_URL>
# - Creates a show with 50 seats
# - Fires 500 concurrent requests for seat A1 (hot seat storm)
# - Fires 1000 concurrent requests spread across all seats
# - Prints: confirmed / declined-by-reason / 5xx counts
# - Prints final reconciliation (available + held + confirmed == total)
```
Uses `curl` + `xargs -P` for parallelism, or a small Go/Python helper.

## docker-compose.yml

```yaml
services:
  db:
    image: postgres:15
    environment:
      POSTGRES_DB: seatreservation
      POSTGRES_USER: app
      POSTGRES_PASSWORD: secret
    ports: ["5432:5432"]
  
  app:
    build: .
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/seatreservation
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: secret
      JWT_SECRET: change-me-in-production
    depends_on:
      db:
        condition: service_healthy
```

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Key Design Decisions

1. **No holds model** — go straight to CONFIRMED. Simpler, no expiry daemon needed. Cancel is explicit.
2. **SELECT FOR UPDATE in label order** — the single atomic step that prevents double-sell and deadlock.
3. **Unique constraint on idempotency key** — database-level backstop for exactly-once.
4. **Integer paise** — all money as BIGINT, never FLOAT/DOUBLE.
5. **Structured JSON logs** — logback with JSON encoder, correlation ID via MDC filter.
6. **HikariCP pool sizing** — tuned for concurrency (max pool = 20, connection timeout = 3s).

## Implementation Order

1. Maven project scaffold + Flyway migrations
2. Models + Repositories (JPA)
3. JWT auth filter
4. ShowService + ShowController
5. ReservationService (atomic core) + ReservationController
6. Cancel endpoint
7. Health endpoints
8. Metrics (Micrometer custom)
9. Structured logging + correlation ID filter
10. Dockerfile + docker-compose
11. burst.sh
12. README.md + WRITEUP.md

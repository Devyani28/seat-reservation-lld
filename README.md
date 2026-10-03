# Seat Reservation Service

A high-correctness seat reservation API built with **Java 21 + Spring Boot 3 + PostgreSQL**.  
Designed to never double-sell a seat, never exceed per-user limits, and never double-charge a retried request — even under 20,000 concurrent buyers.

## Live URL

> Deploy using the instructions below and update this line with your public URL.

## Quick Start (Docker Compose)

```bash
git clone <repo-url>
cd seat-reservation
docker compose up --build
```

The service starts on **http://localhost:8080**.

## One-Command Burst Test

```bash
./burst.sh http://localhost:8080
```

Against a live deployment:

```bash
./burst.sh https://your-app.onrender.com
```

The script runs four phases and prints a full outcome distribution + reconciliation check.

## API Reference

### Authentication

All endpoints (except `/health/*` and `/auth/token`) require a JWT in the `Authorization: Bearer <token>` header.

**Get a token:**
```bash
# User token
curl -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "alice", "role": "USER"}'

# Admin token (required for POST /shows)
curl -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "admin", "role": "ADMIN"}'
```

---

### POST /shows _(admin)_

Create a show with numbered seats.

```bash
curl -X POST http://localhost:8080/shows \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "friday-night",
    "seats": ["A1","A2","A3","A4","A5"],
    "pricePaise": 25000,
    "perUserLimit": 4
  }'
```

**Response 201:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 5,
  "available": 5,
  "held": 0,
  "confirmed": 0,
  "seats": [
    {"id": "...", "label": "A1", "status": "available"},
    ...
  ]
}
```

---

### POST /shows/{id}/reserve _(authenticated user)_

Reserve one or more seats. **All-or-nothing**: if any seat is unavailable, the whole request is declined.

```bash
curl -X POST http://localhost:8080/shows/$SHOW_ID/reserve \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1"],
    "idempotencyKey": "my-unique-request-id-001"
  }'
```

**Response 201 (success):**
```json
{
  "reservation_id": "...",
  "show_id": "...",
  "user_id": "alice",
  "seats": ["A1"],
  "amount_paise": 25000,
  "status": "confirmed",
  "created_at": "2026-10-02T10:00:00Z"
}
```

**Response 409 (declined):**
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Seat(s) already taken: [A1]",
  "reason": "seat_taken"
}
```

Possible `reason` values:
| Reason | Meaning |
|---|---|
| `seat_taken` | One or more requested seats are already confirmed |
| `per_user_limit` | User would exceed their per-show seat limit |
| `idempotent_replay` | Same key, same seats — original reservation returned |
| `key_conflict` | Same key, different seats — rejected |

---

### POST /reservations/{id}/cancel _(owner only)_

Cancel a reservation. Only the token's user may cancel their own reservation.

```bash
curl -X POST http://localhost:8080/reservations/$RESERVATION_ID/cancel \
  -H "Authorization: Bearer $USER_TOKEN"
```

Cancelled seats return to `available` and become re-bookable.

---

### GET /shows/{id}

Get show state with per-seat status and counts.

```bash
curl http://localhost:8080/shows/$SHOW_ID \
  -H "Authorization: Bearer $USER_TOKEN"
```

**Invariant**: `available + held + confirmed == total_seats` always.

---

### GET /health/live

Liveness probe — always 200 if the JVM is running.

### GET /health/ready

Readiness probe — 200 if DB is reachable, 503 if not.

### GET /actuator/prometheus

Prometheus metrics endpoint. Key metrics:

```
reservations_confirmed_total{show_id="..."}
reservations_declined_total{show_id="...", reason="seat_taken|per_user_limit|idempotent_replay|key_conflict"}
seats_available{show_id="..."}
reservation_duration_seconds_bucket{...}
```

---

## Deploy to Render

1. Push this repo to GitHub.
2. Create a new **Web Service** on [Render](https://render.com), pointing to your repo.
3. Set **Environment** to `Docker`.
4. Add a **PostgreSQL** database on Render; copy the internal connection string.
5. Set environment variables:
   ```
   SPRING_DATASOURCE_URL=<render-postgres-internal-url>
   SPRING_DATASOURCE_USERNAME=<user>
   SPRING_DATASOURCE_PASSWORD=<password>
   JWT_SECRET=<at-least-32-random-chars>
   ```
6. Deploy. The service auto-runs Flyway migrations on startup.

## Deploy to Railway

```bash
railway login
railway init
railway add --database postgresql
railway up
```

Set the same environment variables in the Railway dashboard.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/seatreservation` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `app` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `secret` | DB password |
| `JWT_SECRET` | `change-me-...` | HS256 signing key (≥32 chars) |
| `JWT_EXPIRATION_MS` | `86400000` | Token TTL in ms (24h) |
| `HIKARI_MAX_POOL_SIZE` | `20` | Max DB connections |
| `PORT` | `8080` | HTTP port |

## Metrics & Logs

- **Prometheus**: `GET /actuator/prometheus`
- **Logs**: Structured JSON on stdout (Render/Railway stream these to their log dashboards)
- **Correlation ID**: Every request gets an `X-Request-Id` header and `requestId` in every log line

## Architecture Notes

See [WRITEUP.md](WRITEUP.md) for the full design rationale.

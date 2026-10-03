# WRITEUP.md — Seat Reservation Service

## 1. The Atomic Decision: How We Prevent Double-Sells

### Mechanism: `SELECT ... FOR UPDATE` with deterministic ordering

The core of correctness lives in `ReservationService.reserve()`. When a user requests seats, the service:

1. **Acquires exclusive row locks** on the requested seat rows in a single SQL statement:
   ```sql
   SELECT s FROM Seat s
   WHERE s.show.id = :showId AND s.label IN :labels
   ORDER BY s.label ASC
   FOR UPDATE
   ```
   The `FOR UPDATE` clause tells PostgreSQL to take an exclusive row-level lock on each matched row. Any concurrent transaction that tries to lock the same rows will **block** until the first transaction commits or rolls back.

2. **Checks seat status inside the lock.** Because we hold the lock, no other transaction can change the seat status between our read and our write. This eliminates the classic read-then-write race condition.

3. **Updates seat status and inserts the reservation** in the same transaction. The commit releases the locks.

### Why this is race-free

The key insight: with `FOR UPDATE`, the "is this seat available?" check and the "mark it taken" write are a single atomic unit. The second concurrent request for the same seat doesn't race — it **waits** for the first to finish, then sees `status = CONFIRMED` and returns 409. There is no window where two transactions can both see `AVAILABLE` and both proceed.

### Multi-seat deadlock prevention

For requests covering multiple seats (e.g., `["A12", "A13"]`), we always lock rows in **alphabetical label order** (`ORDER BY s.label ASC`). This is the standard deadlock-prevention technique for multi-resource locking:

- Transaction T1 wants [A12, A13]: locks A12 first, then A13.
- Transaction T2 wants [A13, A12]: also locks A12 first (due to ORDER BY), then A13.
- No circular wait → no deadlock.

Without the ordering, T1 could hold A12 and wait for A13 while T2 holds A13 and waits for A12 — a classic deadlock.

---

## 2. Idempotency: Exactly-Once Reservation

### Where the key is stored

The `idempotency_key` is stored in the `reservations` table with a **unique constraint** on `(show_id, idempotency_key)`:

```sql
CONSTRAINT reservations_show_idempotency_unique UNIQUE (show_id, idempotency_key)
```

### How exactly-once is enforced

**Fast path (before locking):** At the start of `reserve()`, we query for an existing reservation with the same `(show_id, idempotency_key)`. If found:
- Same user, same seats → return the original reservation (idempotent replay).
- Same user, different seats → 409 `key_conflict`.
- Different user → 409 `key_conflict`.

**Backstop (race condition):** Two identical requests can race through the fast-path check simultaneously (both see no existing reservation). Only one `INSERT` will succeed; the other hits the unique constraint and gets a `DataIntegrityViolationException`. We catch this, re-read the now-existing reservation, and return it as a replay. This makes the system correct even under extreme concurrency.

### Same-key-different-body handling

If a client sends the same `idempotency_key` with different `seats`, the service returns **409 `key_conflict`**. The key is a commitment: once used, it's bound to the original request body. This prevents clients from accidentally reusing keys across different bookings.

---

## 3. Holds & Expiry

This service uses a **no-holds model**: reservations go directly to `CONFIRMED` status. There is no intermediate `HELD` state in the happy path (the schema supports it for future use).

**Rationale:** Holds add significant complexity — you need an expiry daemon, you need to handle the case where a hold expires while a payment is in flight, and you need to ensure a released hold never resurrects a seat already confirmed to someone else. For this service, the simpler model is:

- Reserve → immediately `CONFIRMED`.
- Cancel → explicit `POST /reservations/{id}/cancel` by the owner.
- A cancelled reservation returns its seats to `AVAILABLE`, making them re-bookable.

**Cancel safety:** The cancel path checks `seat.status == CONFIRMED` before setting it back to `AVAILABLE`. This guards against a theoretical scenario where a seat was somehow re-sold between the cancel request arriving and the transaction executing.

---

## 4. Partial Requests: All-or-Nothing

If a user requests `["A12", "A13"]` and only `A12` is available, the entire request is **declined with 409 `seat_taken`**. No partial booking is created.

**Why:** Best-effort partial booking creates ambiguous state — the user paid for two seats but got one, requiring refund logic. All-or-nothing is simpler, safer, and easier to reason about under concurrency. The client can retry with just the available seat.

---

## 5. Consistency vs. Availability Under a Partition

This service prioritises **consistency over availability** (CP in CAP terms).

- If the PostgreSQL database is unreachable, the `/health/ready` endpoint returns 503 and the service refuses to serve reservation requests. A seat reservation system must never guess — a wrong answer (double-sell) is worse than no answer.
- The readiness probe actively checks DB connectivity (`SELECT 1`) so load balancers can route traffic away from unhealthy instances.
- We use `READ_COMMITTED` isolation with explicit `FOR UPDATE` locks rather than `SERIALIZABLE`, because `SERIALIZABLE` can produce spurious serialization failures under high load. Our explicit locking gives us the same correctness guarantees with better throughput.

---

## 6. Observability: What You'd Get Paged For at 2am

### Metrics (Prometheus at `/actuator/prometheus`)

| Metric | Type | Alert condition |
|---|---|---|
| `reservations_confirmed_total` | Counter | Sudden drop to 0 during on-sale |
| `reservations_declined_total{reason="seat_taken"}` | Counter | Spike expected during hot-seat storm; sustained high rate after show sells out is normal |
| `reservations_declined_total{reason="per_user_limit"}` | Counter | Unexpected spike may indicate abuse |
| `seats_available{show_id="..."}` | Gauge | Goes negative → reconciliation broken (page immediately) |
| `reservation_duration_seconds_p99` | Histogram | > 2s → DB lock contention or pool exhaustion |
| `hikaricp_connections_pending` | Gauge | > 0 sustained → pool too small for load |

### Logs

Every request gets a `requestId` (UUID prefix) in the MDC, emitted as a JSON field in every log line. This lets you trace a single request through all log statements with a simple grep.

Key log events:
- `Reservation confirmed` — with reservation ID, user, show, seats, amount
- `Seat(s) unavailable` — with which seats were taken
- `Per-user limit exceeded` — with current count and limit
- `Idempotent replay` — with original reservation ID
- `Unhandled exception` — with full stack trace (these should never happen; page on any 5xx)

### What would page me at 2am

1. **Any 5xx response** — the system is supposed to turn all domain errors into 4xx. A 5xx means something unexpected happened.
2. **`seats_available` going negative** — the reconciliation invariant is broken; data integrity is compromised.
3. **`reservation_duration_seconds_p99 > 2s`** — lock contention or DB pool exhaustion; the stampede is winning.
4. **`/health/ready` returning 503** — DB is down; all reservations are failing.
5. **`hikaricp_connections_pending > 5` sustained** — connection pool is a bottleneck; scale the pool or the DB.

---

## 7. AI Usage

I used AiNxt CLI to scaffold and implement this service. Specifically:

**Directed (AI generated, I reviewed):**
- Boilerplate: pom.xml dependencies, Spring Boot configuration, Flyway migration SQL, Lombok annotations, DTO classes, Docker/compose files.
- The burst.sh script structure and xargs parallelism pattern.
- Logback JSON encoder configuration.

**Decided (I designed, AI implemented):**
- The core correctness mechanism: `SELECT FOR UPDATE` with `ORDER BY label` for deadlock prevention. I chose this over optimistic locking (too many retries under high contention) and application-level locks (don't survive restarts).
- The idempotency design: unique constraint as backstop + `DataIntegrityViolationException` catch-and-replay for the race case.
- The no-holds model and all-or-nothing partial request policy.
- The decision to use `READ_COMMITTED` + explicit locks rather than `SERIALIZABLE`.
- The metrics design: what to count, what to gauge, what to histogram.

I understand every line of this code and can extend it live.

---

## 8. What I'd Do Next

1. **Distributed tracing** — add OpenTelemetry with Jaeger/Tempo to trace requests across services if this becomes a microservice.
2. **Rate limiting** — per-user and per-IP rate limits to prevent abuse during on-sale stampedes.
3. **Holds with auto-expiry** — a `HELD` state with a configurable TTL (e.g., 10 minutes to complete payment), implemented with a scheduled job or PostgreSQL `pg_cron`.
4. **Payment integration** — a two-phase flow: hold → payment → confirm, with idempotent payment processing.
5. **Read replicas** — route `GET /shows/{id}` to a read replica to reduce load on the primary during bursts.
6. **Seat categories and pricing** — different price tiers (VIP, general) with per-category limits.
7. **Waitlist** — when a show sells out, allow users to join a waitlist and get notified when a cancellation opens a seat.
8. **Admin dashboard** — real-time seat map showing status, connected via WebSocket for live updates during on-sale.
9. **Horizontal scaling** — the current design is stateless (all state in PostgreSQL), so horizontal scaling works out of the box. The bottleneck would be DB connection pool size; PgBouncer in transaction mode would help.
10. **Chaos testing** — inject DB failures, network partitions, and slow queries to verify the health probes and error handling work correctly under real failure conditions.

#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# burst.sh — On-sale stampede simulator for the Seat Reservation service
#
# Usage:
#   ./burst.sh <BASE_URL>
#   ./burst.sh http://localhost:8080
#   ./burst.sh https://your-app.onrender.com
#
# What it does:
#   1. Creates an admin token and a show with 50 seats (A1–A50)
#   2. HOT-SEAT STORM: 500 concurrent users all try to grab seat A1
#      → Exactly 1 must succeed; 499 must get 409 seat_taken
#   3. SPREAD BURST: 1000 concurrent requests across all 50 seats
#      (20 users × 50 seats, each user tries every seat once)
#   4. IDEMPOTENCY TEST: same request fired 5 times with same key
#      → Only 1 confirmation; 4 idempotent replays
#   5. PER-USER LIMIT TEST: 1 user fires 10 parallel requests (limit=4)
#      → At most 4 confirmed
#   6. Prints outcome distribution and final reconciliation
#
# Requirements: curl, jq, xargs (all standard on macOS/Linux)
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY="${2:-50}"   # xargs parallelism

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[burst]${NC} $*"; }
ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }

# ── Helpers ───────────────────────────────────────────────────────────────────

get_token() {
  local user_id="$1"
  local role="${2:-USER}"
  curl -sf -X POST "$BASE_URL/auth/token" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$user_id\",\"role\":\"$role\"}" \
    | jq -r '.token'
}

reserve_seat() {
  local token="$1"
  local show_id="$2"
  local seat="$3"
  local idem_key="$4"
  curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"$seat\"],\"idempotencyKey\":\"$idem_key\"}"
}

reserve_seat_full() {
  local token="$1"
  local show_id="$2"
  local seat="$3"
  local idem_key="$4"
  curl -s \
    -X POST "$BASE_URL/shows/$show_id/reserve" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"$seat\"],\"idempotencyKey\":\"$idem_key\"}"
}

count_outcomes() {
  local file="$1"
  local confirmed declined_seat declined_limit declined_replay declined_key errors
  confirmed=$(grep -c '"status":"confirmed"' "$file" 2>/dev/null || echo 0)
  declined_seat=$(grep -c '"reason":"seat_taken"' "$file" 2>/dev/null || echo 0)
  declined_limit=$(grep -c '"reason":"per_user_limit"' "$file" 2>/dev/null || echo 0)
  declined_replay=$(grep -c '"reason":"idempotent_replay"' "$file" 2>/dev/null || echo 0)
  declined_key=$(grep -c '"reason":"key_conflict"' "$file" 2>/dev/null || echo 0)
  errors=$(grep -c '"status":5' "$file" 2>/dev/null || echo 0)
  echo "  confirmed:        $confirmed"
  echo "  declined/seat_taken:     $declined_seat"
  echo "  declined/per_user_limit: $declined_limit"
  echo "  declined/idempotent_replay: $declined_replay"
  echo "  declined/key_conflict:   $declined_key"
  echo "  5xx errors:       $errors"
}

# ── Preflight ─────────────────────────────────────────────────────────────────

log "Checking service at $BASE_URL ..."
if ! curl -sf "$BASE_URL/health/ready" > /dev/null; then
  fail "Service is not ready at $BASE_URL/health/ready — aborting."
  exit 1
fi
ok "Service is healthy."

# ── Step 1: Create show ───────────────────────────────────────────────────────

log "Creating admin token..."
ADMIN_TOKEN=$(get_token "admin-burst" "ADMIN")

log "Creating show with 50 seats..."
SEATS_JSON=$(python3 -c "import json; print(json.dumps([f'A{i}' for i in range(1,51)]))" 2>/dev/null \
  || node -e "console.log(JSON.stringify(Array.from({length:50},(_,i)=>'A'+(i+1))))" 2>/dev/null \
  || echo '["A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12","A13","A14","A15","A16","A17","A18","A19","A20","A21","A22","A23","A24","A25","A26","A27","A28","A29","A30","A31","A32","A33","A34","A35","A36","A37","A38","A39","A40","A41","A42","A43","A44","A45","A46","A47","A48","A49","A50"]')

SHOW_RESP=$(curl -sf -X POST "$BASE_URL/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"burst-test-$(date +%s)\",\"seats\":$SEATS_JSON,\"pricePaise\":25000,\"perUserLimit\":4}")

SHOW_ID=$(echo "$SHOW_RESP" | jq -r '.id')
TOTAL=$(echo "$SHOW_RESP" | jq -r '.total_seats')
ok "Show created: id=$SHOW_ID total_seats=$TOTAL"

# ── Step 2: Hot-seat storm (500 users → seat A1) ──────────────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "PHASE 1: HOT-SEAT STORM — 500 users → seat A1"
log "═══════════════════════════════════════════════════"

TMPDIR_BURST=$(mktemp -d)
HOT_RESULTS="$TMPDIR_BURST/hot_results.txt"
> "$HOT_RESULTS"

# Pre-generate 500 tokens (one per user)
log "Pre-generating 500 user tokens..."
TOKEN_FILE="$TMPDIR_BURST/tokens.txt"
for i in $(seq 1 500); do
  echo "hot-user-$i"
done | xargs -P "$CONCURRENCY" -I{} bash -c "
  TOKEN=\$(curl -sf -X POST '$BASE_URL/auth/token' \
    -H 'Content-Type: application/json' \
    -d '{\"userId\":\"{}\",\"role\":\"USER\"}' | jq -r '.token')
  echo \"{} \$TOKEN\"
" > "$TOKEN_FILE"

log "Firing 500 concurrent requests for seat A1..."
START_HOT=$(date +%s%N)

while IFS=' ' read -r user_id token; do
  echo "$user_id $token"
done < "$TOKEN_FILE" | xargs -P "$CONCURRENCY" -I{} bash -c "
  read -r USER TOKEN <<< '{}'
  IDEM=\"hot-\${USER}-A1-burst\"
  RESP=\$(curl -s -X POST '$BASE_URL/shows/$SHOW_ID/reserve' \
    -H 'Authorization: Bearer \$TOKEN' \
    -H 'Content-Type: application/json' \
    -d \"{\\\"seats\\\":[\\\"A1\\\"],\\\"idempotencyKey\\\":\\\"\$IDEM\\\"}\")
  echo \"\$RESP\"
" >> "$HOT_RESULTS" 2>/dev/null || true

END_HOT=$(date +%s%N)
HOT_MS=$(( (END_HOT - START_HOT) / 1000000 ))

echo ""
echo "Hot-seat storm results (${HOT_MS}ms total):"
count_outcomes "$HOT_RESULTS"

HOT_CONFIRMED=$(grep -c '"status":"confirmed"' "$HOT_RESULTS" 2>/dev/null || echo 0)
if [ "$HOT_CONFIRMED" -eq 1 ]; then
  ok "✓ Exactly 1 confirmation for seat A1 — no double-sell!"
elif [ "$HOT_CONFIRMED" -eq 0 ]; then
  warn "⚠ 0 confirmations for seat A1 — something went wrong"
else
  fail "✗ $HOT_CONFIRMED confirmations for seat A1 — DOUBLE-SELL DETECTED!"
fi

# ── Step 3: Spread burst (1000 requests across all seats) ─────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "PHASE 2: SPREAD BURST — 1000 requests across 50 seats"
log "═══════════════════════════════════════════════════"

SPREAD_RESULTS="$TMPDIR_BURST/spread_results.txt"
> "$SPREAD_RESULTS"

# 20 users × 50 seats = 1000 requests
log "Firing 1000 concurrent requests (20 users × 50 seats)..."
START_SPREAD=$(date +%s%N)

for user_num in $(seq 1 20); do
  for seat_num in $(seq 1 50); do
    echo "spread-user-$user_num A$seat_num"
  done
done | xargs -P "$CONCURRENCY" -I{} bash -c "
  read -r USER SEAT <<< '{}'
  TOKEN=\$(curl -sf -X POST '$BASE_URL/auth/token' \
    -H 'Content-Type: application/json' \
    -d \"{\\\"userId\\\":\\\"\$USER\\\",\\\"role\\\":\\\"USER\\\"}\" | jq -r '.token')
  IDEM=\"spread-\${USER}-\${SEAT}-burst\"
  RESP=\$(curl -s -X POST '$BASE_URL/shows/$SHOW_ID/reserve' \
    -H 'Authorization: Bearer \$TOKEN' \
    -H 'Content-Type: application/json' \
    -d \"{\\\"seats\\\":[\\\"\$SEAT\\\"],\\\"idempotencyKey\\\":\\\"\$IDEM\\\"}\")
  echo \"\$RESP\"
" >> "$SPREAD_RESULTS" 2>/dev/null || true

END_SPREAD=$(date +%s%N)
SPREAD_MS=$(( (END_SPREAD - START_SPREAD) / 1000000 ))

echo ""
echo "Spread burst results (${SPREAD_MS}ms total):"
count_outcomes "$SPREAD_RESULTS"

# ── Step 4: Idempotency test ──────────────────────────────────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "PHASE 3: IDEMPOTENCY — same key fired 5 times"
log "═══════════════════════════════════════════════════"

IDEM_TOKEN=$(get_token "idem-test-user" "USER")
IDEM_KEY="idempotency-test-key-$(date +%s)"
IDEM_RESULTS="$TMPDIR_BURST/idem_results.txt"
> "$IDEM_RESULTS"

# Find an available seat
AVAIL_SEAT=$(curl -sf "$BASE_URL/shows/$SHOW_ID" \
  -H "Authorization: Bearer $IDEM_TOKEN" \
  | jq -r '.seats[] | select(.status=="available") | .label' | head -1)

if [ -z "$AVAIL_SEAT" ]; then
  warn "No available seats for idempotency test (all taken by spread burst)"
  AVAIL_SEAT="A49"
fi

log "Firing same request 5× with key=$IDEM_KEY seat=$AVAIL_SEAT ..."
for i in $(seq 1 5); do echo "$i"; done | xargs -P 5 -I{} bash -c "
  RESP=\$(curl -s -X POST '$BASE_URL/shows/$SHOW_ID/reserve' \
    -H 'Authorization: Bearer $IDEM_TOKEN' \
    -H 'Content-Type: application/json' \
    -d '{\"seats\":[\"$AVAIL_SEAT\"],\"idempotencyKey\":\"$IDEM_KEY\"}')
  echo \"\$RESP\"
" >> "$IDEM_RESULTS" 2>/dev/null || true

echo ""
echo "Idempotency test results:"
count_outcomes "$IDEM_RESULTS"

IDEM_CONFIRMED=$(grep -c '"status":"confirmed"' "$IDEM_RESULTS" 2>/dev/null || echo 0)
if [ "$IDEM_CONFIRMED" -le 1 ]; then
  ok "✓ At most 1 confirmation — idempotency holds!"
else
  fail "✗ $IDEM_CONFIRMED confirmations for same key — IDEMPOTENCY BROKEN!"
fi

# ── Step 5: Per-user limit test ───────────────────────────────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "PHASE 4: PER-USER LIMIT — 10 parallel requests, limit=4"
log "═══════════════════════════════════════════════════"

LIMIT_TOKEN=$(get_token "limit-test-user" "USER")
LIMIT_RESULTS="$TMPDIR_BURST/limit_results.txt"
> "$LIMIT_RESULTS"

# Get 10 available seats
AVAIL_SEATS=$(curl -sf "$BASE_URL/shows/$SHOW_ID" \
  -H "Authorization: Bearer $LIMIT_TOKEN" \
  | jq -r '[.seats[] | select(.status=="available") | .label] | .[:10] | .[]' 2>/dev/null || echo "")

if [ -z "$AVAIL_SEATS" ]; then
  warn "Not enough available seats for per-user limit test — skipping"
else
  log "Firing 10 parallel requests for different seats (limit=4)..."
  echo "$AVAIL_SEATS" | head -10 | xargs -P 10 -I{} bash -c "
    SEAT='{}'
    IDEM=\"limit-test-\$SEAT-$(date +%s)\"
    RESP=\$(curl -s -X POST '$BASE_URL/shows/$SHOW_ID/reserve' \
      -H 'Authorization: Bearer $LIMIT_TOKEN' \
      -H 'Content-Type: application/json' \
      -d \"{\\\"seats\\\":[\\\"\$SEAT\\\"],\\\"idempotencyKey\\\":\\\"\$IDEM\\\"}\")
    echo \"\$RESP\"
  " >> "$LIMIT_RESULTS" 2>/dev/null || true

  echo ""
  echo "Per-user limit test results:"
  count_outcomes "$LIMIT_RESULTS"

  LIMIT_CONFIRMED=$(grep -c '"status":"confirmed"' "$LIMIT_RESULTS" 2>/dev/null || echo 0)
  if [ "$LIMIT_CONFIRMED" -le 4 ]; then
    ok "✓ $LIMIT_CONFIRMED ≤ 4 confirmations — per-user limit holds!"
  else
    fail "✗ $LIMIT_CONFIRMED confirmations — PER-USER LIMIT BROKEN!"
  fi
fi

# ── Step 6: Reconciliation ────────────────────────────────────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "RECONCILIATION CHECK"
log "═══════════════════════════════════════════════════"

USER_TOKEN=$(get_token "reconcile-user" "USER")
SHOW_STATE=$(curl -sf "$BASE_URL/shows/$SHOW_ID" \
  -H "Authorization: Bearer $USER_TOKEN")

AVAIL=$(echo "$SHOW_STATE" | jq -r '.available')
HELD=$(echo "$SHOW_STATE" | jq -r '.held')
CONF=$(echo "$SHOW_STATE" | jq -r '.confirmed')
TOTAL_CHECK=$(echo "$SHOW_STATE" | jq -r '.total_seats')
SUM=$((AVAIL + HELD + CONF))

echo ""
echo "Show state after all bursts:"
echo "  available:  $AVAIL"
echo "  held:       $HELD"
echo "  confirmed:  $CONF"
echo "  total:      $TOTAL_CHECK"
echo "  sum(a+h+c): $SUM"

if [ "$SUM" -eq "$TOTAL_CHECK" ]; then
  ok "✓ Reconciliation invariant holds: $AVAIL + $HELD + $CONF = $TOTAL_CHECK"
else
  fail "✗ Reconciliation BROKEN: $AVAIL + $HELD + $CONF = $SUM ≠ $TOTAL_CHECK"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

log ""
log "═══════════════════════════════════════════════════"
log "BURST COMPLETE"
log "═══════════════════════════════════════════════════"
echo "  Hot-seat storm:  ${HOT_MS}ms"
echo "  Spread burst:    ${SPREAD_MS}ms"
echo ""
echo "Check Prometheus metrics at: $BASE_URL/actuator/prometheus"
echo "  grep 'reservations_confirmed_total'"
echo "  grep 'reservations_declined_total'"
echo "  grep 'seats_available'"

# Cleanup
rm -rf "$TMPDIR_BURST"

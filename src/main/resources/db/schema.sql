-- Idempotent schema creation (CREATE TABLE IF NOT EXISTS)
-- Separator: ;;;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";;;

CREATE TABLE IF NOT EXISTS shows (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4,
    total_seats    INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);;;

CREATE TABLE IF NOT EXISTS seats (
    id       UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id  UUID  NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    label    TEXT  NOT NULL,
    status   TEXT  NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT seats_show_label_unique UNIQUE (show_id, label),
    CONSTRAINT seats_status_check CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED'))
);;;

CREATE INDEX IF NOT EXISTS idx_seats_show_status ON seats (show_id, status);;;

CREATE TABLE IF NOT EXISTS reservations (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id          UUID        NOT NULL REFERENCES shows(id),
    user_id          TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'CONFIRMED',
    amount_paise     BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reservations_status_check CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT reservations_show_idempotency_unique UNIQUE (show_id, idempotency_key)
);;;

CREATE INDEX IF NOT EXISTS idx_reservations_user_show ON reservations (user_id, show_id, status);;;

CREATE TABLE IF NOT EXISTS reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    seat_id        UUID NOT NULL REFERENCES seats(id),
    PRIMARY KEY (reservation_id, seat_id)
);;;

CREATE INDEX IF NOT EXISTS idx_reservation_seats_seat ON reservation_seats (seat_id);;;

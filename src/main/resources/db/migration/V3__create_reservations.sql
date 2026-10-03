CREATE TABLE reservations (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id          UUID        NOT NULL REFERENCES shows(id),
    user_id          TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'CONFIRMED',
    amount_paise     BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reservations_status_check CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    -- Exactly-once per show: same key on same show always returns same result
    CONSTRAINT reservations_show_idempotency_unique UNIQUE (show_id, idempotency_key)
);

CREATE INDEX idx_reservations_user_show ON reservations (user_id, show_id, status);

CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    seat_id        UUID NOT NULL REFERENCES seats(id),
    PRIMARY KEY (reservation_id, seat_id)
);

CREATE INDEX idx_reservation_seats_seat ON reservation_seats (seat_id);

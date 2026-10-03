CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE shows (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4,
    total_seats    INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    id       UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id  UUID  NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    label    TEXT  NOT NULL,
    status   TEXT  NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT seats_show_label_unique UNIQUE (show_id, label),
    CONSTRAINT seats_status_check CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED'))
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);

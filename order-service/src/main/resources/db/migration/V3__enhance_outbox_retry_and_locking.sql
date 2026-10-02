ALTER TABLE outbox_events
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN last_error TEXT,
    ADD COLUMN locked_at TIMESTAMPTZ,
    ADD COLUMN locked_by VARCHAR(100);

CREATE INDEX idx_outbox_events_publishable
    ON outbox_events (status, next_attempt_at, created_at);
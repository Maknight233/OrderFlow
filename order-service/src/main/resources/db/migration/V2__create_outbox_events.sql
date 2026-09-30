CREATE TABLE outbox_events (
                               id UUID PRIMARY KEY,
                               aggregate_type VARCHAR(100) NOT NULL,
                               aggregate_id UUID NOT NULL,
                               event_type VARCHAR(100) NOT NULL,
                               payload TEXT NOT NULL,
                               status VARCHAR(20) NOT NULL,
                               created_at TIMESTAMP NOT NULL,
                               published_at TIMESTAMP
);

CREATE INDEX idx_outbox_events_status_created_at
    ON outbox_events(status, created_at);
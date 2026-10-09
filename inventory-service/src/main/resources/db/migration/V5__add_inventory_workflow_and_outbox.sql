CREATE TABLE inventory_workflows (
    order_id UUID PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(2000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_inventory_workflows_state ON inventory_workflows (state);

-- Preserve final legacy reservation states; re-evaluate unfinished reservations on reserve.
INSERT INTO inventory_workflows (order_id, state)
SELECT order_id,
       CASE WHEN bool_and(status = 'CONFIRMED') THEN 'CONFIRMED'
            WHEN bool_and(status = 'RELEASED') THEN 'RELEASED'
            ELSE 'NEW'
       END
FROM inventory_reservations
GROUP BY order_id;

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100)
);
CREATE INDEX idx_inventory_outbox_publishable ON outbox_events (status, next_attempt_at, created_at);

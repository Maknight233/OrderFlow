CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    sku VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    UNIQUE (order_id, sku)
);

CREATE TABLE order_sagas (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    state VARCHAR(32) NOT NULL,
    recovery_state VARCHAR(32),
    failure_reason VARCHAR(2000),
    deadline TIMESTAMPTZ NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_order_sagas_deadline ON order_sagas (state, deadline);

CREATE TABLE processed_inventory_events (
    event_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Recover persisted item lists from Day 7/8 events before starting the workflow.
INSERT INTO order_items (id, order_id, sku, quantity)
SELECT gen_random_uuid(), o.id, item->>'sku', SUM((item->>'quantity')::INTEGER)::INTEGER
FROM orders o
JOIN outbox_events e ON e.aggregate_id = o.id AND e.event_type = 'ORDER_CREATED'
CROSS JOIN LATERAL jsonb_array_elements(e.payload::jsonb->'items') item
GROUP BY o.id, item->>'sku';

-- Legacy confirmed orders still need to confirm their inventory reservations.
INSERT INTO order_sagas (order_id, state, recovery_state, failure_reason, deadline)
SELECT o.id,
       CASE
           WHEN o.status = 'COMPLETED' THEN 'COMPLETED'
           WHEN o.status = 'CANCELLED' THEN 'COMPENSATING'
           WHEN NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id) THEN 'MANUAL_RECOVERY'
           WHEN o.status = 'CONFIRMED' THEN 'WAITING_CONFIRMATION'
           ELSE 'WAITING_RESERVATION'
       END,
       CASE WHEN o.status IN ('PENDING', 'CONFIRMED')
                 AND NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id)
            THEN CASE WHEN o.status = 'CONFIRMED' THEN 'WAITING_CONFIRMATION' ELSE 'WAITING_RESERVATION' END
       END,
       CASE WHEN o.status IN ('PENDING', 'CONFIRMED')
                 AND NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id)
            THEN 'Legacy order has no persisted items; review before recovery'
       END,
       NOW()
FROM orders o;

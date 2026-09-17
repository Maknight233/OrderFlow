CREATE TABLE orders
(
    id           UUID PRIMARY KEY,
    customer_id  UUID           NOT NULL,
    status       VARCHAR(32)    NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'USD',
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_orders_total_amount
        CHECK (total_amount >= 0)
);

CREATE INDEX idx_orders_customer_id
    ON orders (customer_id);

CREATE INDEX idx_orders_status
    ON orders (status);
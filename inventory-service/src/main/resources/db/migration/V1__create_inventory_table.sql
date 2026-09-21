CREATE TABLE inventory
(
    id           UUID PRIMARY KEY,
    sku          VARCHAR(100) NOT NULL UNIQUE,
    product_name VARCHAR(255) NOT NULL,
    quantity     INTEGER      NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_inventory_quantity
        CHECK (quantity >= 0)
);
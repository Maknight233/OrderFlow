ALTER TABLE inventory
    ADD COLUMN reserved_quantity INTEGER NOT NULL DEFAULT 0;

ALTER TABLE inventory
    ADD CONSTRAINT chk_inventory_reserved_quantity
        CHECK (reserved_quantity >= 0);


CREATE TABLE inventory_reservations
(
    id          UUID PRIMARY KEY,
    order_id    UUID         NOT NULL,
    sku         VARCHAR(100) NOT NULL,
    quantity    INTEGER      NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_inventory_reservation_quantity
        CHECK (quantity > 0),

    CONSTRAINT uk_inventory_reservation_order_sku
        UNIQUE (order_id, sku)
);

CREATE INDEX idx_inventory_reservations_order_id
    ON inventory_reservations (order_id);

CREATE INDEX idx_inventory_reservations_status
    ON inventory_reservations (status);
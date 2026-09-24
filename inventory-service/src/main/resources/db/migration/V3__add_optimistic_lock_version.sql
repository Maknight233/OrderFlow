ALTER TABLE inventory_reservations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
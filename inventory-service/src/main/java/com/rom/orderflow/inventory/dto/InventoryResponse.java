package com.rom.orderflow.inventory.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InventoryResponse(
        UUID id,
        String sku,
        String productName,
        Integer quantity,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
package com.rom.orderflow.inventory.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record InventoryResultEvent(
        UUID eventId,
        UUID orderId,
        String status,
        String reason,
        Instant occurredAt
) {
}
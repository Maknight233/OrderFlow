package com.rom.orderflow.inventory.kafka.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        UUID orderId,
        List<OrderItemEvent> items,
        Instant occurredAt
) {
}
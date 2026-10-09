package com.rom.orderflow.order.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowEvent(
        UUID eventId, UUID orderId, String eventType, Integer schemaVersion,
        List<OrderItemEvent> items, String status, String reason, Instant occurredAt) {

    public static WorkflowEvent create(UUID orderId, String type, List<OrderItemEvent> items, String reason) {
        return new WorkflowEvent(UUID.randomUUID(), orderId, type, 1, items, null, reason, Instant.now());
    }

    public void validate() {
        if (eventId == null || orderId == null || occurredAt == null) {
            throw new IllegalArgumentException("eventId, orderId and occurredAt are required");
        }
        if (schemaVersion != null && schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported event schema version: " + schemaVersion);
        }
    }

    public String resolvedType() {
        if (eventType != null && !eventType.isBlank()) return eventType;
        // Compatibility with persisted Day 7/8 events.
        // Legacy OrderCreated also contains status=PENDING; items identify the command.
        if (items != null) return "ORDER_CREATED";
        if (status != null) return "INVENTORY_" + status;
        return "ORDER_CREATED";
    }
}

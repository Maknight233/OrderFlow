package com.rom.orderflow.order.kafka.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemEvent> items,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
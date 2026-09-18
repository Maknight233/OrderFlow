package com.rom.orderflow.order.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        String currency,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
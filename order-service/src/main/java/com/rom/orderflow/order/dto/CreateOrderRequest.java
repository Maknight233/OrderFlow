package com.rom.orderflow.order.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        UUID customerId,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemRequest> items
) {
}
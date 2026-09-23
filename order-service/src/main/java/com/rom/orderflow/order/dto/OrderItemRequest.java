package com.rom.orderflow.order.dto;

public record OrderItemRequest(
        String sku,
        int quantity
) {
}
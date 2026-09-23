package com.rom.orderflow.order.kafka.event;

public record OrderItemEvent(
        String sku,
        int quantity
) {
}
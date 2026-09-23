package com.rom.orderflow.inventory.kafka.event;

public record OrderItemEvent(
        String sku,
        int quantity
) {
}
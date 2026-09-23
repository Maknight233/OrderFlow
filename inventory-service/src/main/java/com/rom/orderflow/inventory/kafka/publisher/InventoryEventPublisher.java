package com.rom.orderflow.inventory.kafka.publisher;

import com.rom.orderflow.inventory.kafka.event.InventoryResultEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventPublisher {

    private static final String INVENTORY_EVENTS_TOPIC = "inventory-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventoryEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(InventoryResultEvent event) {
        kafkaTemplate.send(
                INVENTORY_EVENTS_TOPIC,
                event.orderId().toString(),
                event
        );
    }
}
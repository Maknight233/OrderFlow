package com.rom.orderflow.inventory.kafka.consumer;

import com.rom.orderflow.inventory.kafka.event.WorkflowEvent;
import com.rom.orderflow.inventory.workflow.InventoryWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCreatedEventConsumer {
    private final InventoryWorkflowService service;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void consume(WorkflowEvent event) {
        service.handle(event);
    }
}

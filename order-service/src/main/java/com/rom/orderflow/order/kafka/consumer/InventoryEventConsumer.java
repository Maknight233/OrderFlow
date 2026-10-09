package com.rom.orderflow.order.kafka.consumer;

import com.rom.orderflow.order.kafka.event.WorkflowEvent;
import com.rom.orderflow.order.saga.OrderSagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {
    private final OrderSagaService sagaService;

    @KafkaListener(topics = "inventory-events", groupId = "order-service")
    public void consume(WorkflowEvent event) {
        sagaService.handle(event);
    }
}

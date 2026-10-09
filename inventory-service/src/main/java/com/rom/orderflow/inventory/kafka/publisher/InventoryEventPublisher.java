package com.rom.orderflow.inventory.kafka.publisher;

import com.rom.orderflow.inventory.kafka.event.WorkflowEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class InventoryEventPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CompletableFuture<SendResult<String, Object>> publish(WorkflowEvent event) {
        return kafkaTemplate.send("inventory-events", event.orderId().toString(), event);
    }
}

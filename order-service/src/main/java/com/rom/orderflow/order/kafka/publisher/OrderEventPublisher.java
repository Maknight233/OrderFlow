package com.rom.orderflow.order.kafka.publisher;

import com.rom.orderflow.order.kafka.event.WorkflowEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CompletableFuture<SendResult<String, Object>> publish(WorkflowEvent event) {
        return kafkaTemplate.send("order-events", event.orderId().toString(), event);
    }
}

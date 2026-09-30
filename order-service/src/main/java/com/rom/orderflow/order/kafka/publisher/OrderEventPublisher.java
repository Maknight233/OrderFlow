package com.rom.orderflow.order.kafka.publisher;

import com.rom.orderflow.order.kafka.event.OrderCreatedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class OrderEventPublisher {

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, Object>> publishOrderCreated(OrderCreatedEvent event) {

        return kafkaTemplate.send(
                ORDER_EVENTS_TOPIC,
                event.orderId().toString(),
                event
        );
    }
}
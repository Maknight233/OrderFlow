package com.rom.orderflow.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rom.orderflow.order.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.order.kafka.publisher.OrderEventPublisher;
import com.rom.orderflow.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxEventRepository
                        .findTop100ByStatusOrderByCreatedAtAsc(
                                OutboxStatus.PENDING
                        );

        for (OutboxEvent outboxEvent : events) {

            try {

                if ("ORDER_CREATED".equals(outboxEvent.getEventType())) {

                    OrderCreatedEvent event =
                            objectMapper.readValue(
                                    outboxEvent.getPayload(),
                                    OrderCreatedEvent.class
                            );

                    orderEventPublisher
                            .publishOrderCreated(event)
                            .join();

                    outboxEvent.setStatus(OutboxStatus.PUBLISHED);
                    outboxEvent.setPublishedAt(LocalDateTime.now());

                    log.info(
                            "Outbox event published successfully: eventId={}, orderId={}",
                            outboxEvent.getId(),
                            outboxEvent.getAggregateId()
                    );
                }

            } catch (Exception e) {

                log.error(
                        "Failed to publish outbox event: eventId={}",
                        outboxEvent.getId(),
                        e
                );
            }
        }
    }
}
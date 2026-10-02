package com.rom.orderflow.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rom.orderflow.order.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.order.kafka.publisher.OrderEventPublisher;
import com.rom.orderflow.order.service.OutboxClaimService;
import com.rom.orderflow.order.service.OutboxStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int BATCH_SIZE = 1;

    private final OutboxClaimService outboxClaimService;
    private final OutboxStateService outboxStateService;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    public void publishPendingEvents() {


        List<OutboxEvent> events =
                outboxClaimService.claimBatch(BATCH_SIZE);


        for (OutboxEvent outboxEvent : events) {

            try {

                if ("ORDER_CREATED".equals(
                        outboxEvent.getEventType())) {

                    OrderCreatedEvent event =
                            objectMapper.readValue(
                                    outboxEvent.getPayload(),
                                    OrderCreatedEvent.class
                            );

                    orderEventPublisher
                            .publishOrderCreated(event)
                            .get(5, TimeUnit.SECONDS);

                    outboxStateService.markPublished(
                            outboxEvent.getId()
                    );

                    log.info(
                            "Outbox event published successfully: eventId={}, orderId={}",
                            outboxEvent.getId(),
                            outboxEvent.getAggregateId()
                    );
                }

            } catch (Exception e) {

                outboxStateService.markFailed(
                        outboxEvent.getId(),
                        e
                );

                log.error(
                        "Failed to publish outbox event: eventId={}",
                        outboxEvent.getId(),
                        e
                );
            }
        }
    }
}
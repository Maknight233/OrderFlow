package com.rom.orderflow.order.outbox;

import com.rom.orderflow.order.kafka.event.WorkflowEvent;
import com.rom.orderflow.order.kafka.publisher.OrderEventPublisher;
import com.rom.orderflow.order.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.rom.orderflow.order.observability.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final OutboxClaimService claimService;
    private final OutboxStateService stateService;
    private final OrderEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final OutboxTracing tracing;
    private final BusinessMetrics metrics;
    @Value("${orderflow.outbox.batch-size:10}")
    private int batchSize;
    private static final Set<String> SUPPORTED = Set.of("ORDER_CREATED", "INVENTORY_CONFIRM_REQUESTED", "INVENTORY_RELEASE_REQUESTED");

    @Scheduled(fixedDelayString = "${orderflow.outbox.publish-delay-ms:2000}")
    public void publishPendingEvents() {
        for (OutboxEvent row : claimService.claimBatch(batchSize)) {
            long started = System.nanoTime();
            boolean success = false;
            try (var scope = tracing.restore(row.getTraceParent(), row.getAggregateId(), row.getId(), row.getEventType())) {
                try {
                    if (!SUPPORTED.contains(row.getEventType())) {
                        throw new IllegalArgumentException("Unsupported outbox event: " + row.getEventType());
                    }
                    WorkflowEvent event = objectMapper.readValue(row.getPayload(), WorkflowEvent.class);
                    event.validate();
                    if (!row.getEventType().equals(event.resolvedType())
                            || !row.getId().equals(event.eventId())
                            || !row.getAggregateId().equals(event.orderId())) {
                        throw new IllegalArgumentException("Outbox metadata does not match payload");
                    }
                    publisher.publish(event).get(5, TimeUnit.SECONDS);
                    stateService.markPublished(row.getId(), row.getLockedBy());
                    success = true;
                    log.atInfo().addKeyValue("orderId", row.getAggregateId()).addKeyValue("eventId", row.getId())
                            .addKeyValue("eventType", row.getEventType()).addKeyValue("status", "PUBLISHED")
                            .log("Outbox event published");
                } catch (Exception ex) {
                    stateService.markFailed(row.getId(), row.getLockedBy(), ex);
                    log.atWarn().addKeyValue("orderId", row.getAggregateId()).addKeyValue("eventId", row.getId())
                            .addKeyValue("eventType", row.getEventType()).addKeyValue("reason", ex.toString())
                            .setCause(ex).log("Outbox publishing failed");
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                metrics.publishAttempt(success, row.getRetryCount() > 0, started);
            }
        }
    }
}

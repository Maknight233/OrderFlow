package com.rom.orderflow.order.service;

import com.rom.orderflow.order.outbox.*;
import com.rom.orderflow.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxStateService {
    private final OutboxEventRepository repository;
    @Value("${orderflow.outbox.max-attempts:5}")
    private int maxAttempts;

    @Transactional
    public void markPublished(UUID eventId, String token) {
        if (repository.markPublished(eventId, token) == 0) {
            log.warn("Ignored acknowledgement from expired outbox claim: eventId={}", eventId);
        }
    }

    @Transactional
    public void markFailed(UUID eventId, String token, Exception exception) {
        OutboxEvent event = repository.findForUpdate(eventId).orElseThrow();
        if (event.getStatus() != OutboxStatus.PROCESSING || !token.equals(event.getLockedBy())) {
            log.warn("Ignored failure from expired outbox claim: eventId={}", eventId);
            return;
        }
        int attempt = event.getRetryCount() + 1;
        event.setRetryCount(attempt);
        String error = exception.toString();
        event.setLastError(error.substring(0, Math.min(error.length(), 2000)));
        event.setLockedAt(null);
        event.setLockedBy(null);
        if (attempt >= maxAttempts) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Outbox exhausted publishing attempts: eventId={}, aggregateId={}",
                    eventId, event.getAggregateId());
        } else {
            event.setStatus(OutboxStatus.PENDING);
            long delay = Math.min(300, 5L << Math.min(attempt - 1, 6));
            event.setNextAttemptAt(Instant.now().plusSeconds(delay));
        }
    }
}

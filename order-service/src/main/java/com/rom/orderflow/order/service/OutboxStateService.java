package com.rom.orderflow.order.service;

import com.rom.orderflow.order.outbox.OutboxEvent;
import com.rom.orderflow.order.outbox.OutboxStatus;
import com.rom.orderflow.order.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OutboxStateService {

    private static final int MAX_RETRIES = 5;
    private static final Duration BASE_DELAY = Duration.ofSeconds(5);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxStateService(
            OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public void markPublished(UUID eventId) {

        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Outbox event not found: " + eventId));

        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(LocalDateTime.now());

        event.setLockedAt(null);
        event.setLockedBy(null);
        event.setLastError(null);
    }

    @Transactional
    public void markFailed(UUID eventId, Exception exception) {

        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Outbox event not found: " + eventId));

        int retryCount = event.getRetryCount() + 1;

        event.setRetryCount(retryCount);

        String errorMessage = exception.getMessage();

        if (errorMessage != null && errorMessage.length() > 2000) {
            errorMessage = errorMessage.substring(0, 2000);
        }

        event.setLastError(errorMessage);

        event.setLockedAt(null);
        event.setLockedBy(null);

        if (retryCount >= MAX_RETRIES) {

            event.setStatus(OutboxStatus.FAILED);

        } else {

            event.setStatus(OutboxStatus.PENDING);

            long delaySeconds =
                    BASE_DELAY.getSeconds()
                            * (1L << (retryCount - 1));

            event.setNextAttemptAt(
                    Instant.now().plusSeconds(delaySeconds)
            );
        }
    }
}
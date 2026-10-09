package com.rom.orderflow.inventory.service;

import com.rom.orderflow.inventory.outbox.*;
import com.rom.orderflow.inventory.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {
    private final OutboxEventRepository repository;
    private final String workerId = UUID.randomUUID().toString();
    @Value("${orderflow.outbox.processing-timeout-seconds:120}")
    private long processingTimeoutSeconds;

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize) {
        Instant now = Instant.now();
        List<OutboxEvent> events = repository.findClaimableEvents(
                now, now.minusSeconds(processingTimeoutSeconds), batchSize);
        for (OutboxEvent event : events) {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setLockedAt(now);
            // A fresh token for every claim also fences older claims by the same process.
            event.setLockedBy(workerId + ":" + UUID.randomUUID());
        }
        return events;
    }
}

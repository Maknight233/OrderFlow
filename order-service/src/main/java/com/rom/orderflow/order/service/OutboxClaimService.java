package com.rom.orderflow.order.service;

import com.rom.orderflow.order.outbox.OutboxEvent;
import com.rom.orderflow.order.outbox.OutboxStatus;
import com.rom.orderflow.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OutboxClaimService {

    private static final Duration PROCESSING_TIMEOUT =
            Duration.ofMinutes(2);

    private final OutboxEventRepository outboxEventRepository;

    /*
     * 每个 order-service 实例启动后都会获得不同 workerId。
     *
     * 本地启动两个实例时：
     *
     * instance A → 7e2f...
     * instance B → 1a9c...
     */
    private final String workerId =
            UUID.randomUUID().toString();

    public OutboxClaimService(
            OutboxEventRepository outboxEventRepository) {

        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize) {

        Instant now = Instant.now();

        Instant staleBefore =
                now.minus(PROCESSING_TIMEOUT);

        List<OutboxEvent> events =
                outboxEventRepository.findClaimableEvents(
                        now,
                        staleBefore,
                        batchSize
                );

        for (OutboxEvent event : events) {

            event.setStatus(OutboxStatus.PROCESSING);
            event.setLockedAt(now);
            event.setLockedBy(workerId);

            log.info(
                    "Outbox claimed: workerId={}, eventId={}, orderId={}",
                    workerId,
                    event.getId(),
                    event.getAggregateId()
            );
        }

        return events;
    }
}

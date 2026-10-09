package com.rom.orderflow.inventory.outbox;

import com.rom.orderflow.inventory.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxFailureMonitor {
    private final OutboxEventRepository repository;

    @Scheduled(fixedDelayString = "${orderflow.outbox.failure-scan-delay-ms:60000}")
    public void reportFailures() {
        var failed = repository.findByStatusOrderByCreatedAtAsc(OutboxStatus.FAILED, PageRequest.of(0, 20));
        if (!failed.isEmpty()) {
            log.error("Outbox events need operator retry: eventIds={}",
                    failed.stream().map(OutboxEvent::getId).toList());
        }
    }
}

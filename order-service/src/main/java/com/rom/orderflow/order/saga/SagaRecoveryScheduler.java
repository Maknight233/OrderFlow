package com.rom.orderflow.order.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaRecoveryScheduler {
    private final OrderSagaRepository repository;
    private final OrderSagaService service;

    @Scheduled(fixedDelayString = "${orderflow.saga.recovery-delay-ms:5000}")
    public void recover() {
        List<UUID> ids = repository.findExpired(List.of(SagaState.WAITING_RESERVATION,
                SagaState.WAITING_CONFIRMATION, SagaState.COMPENSATING), Instant.now(), PageRequest.of(0, 100));
        for (UUID id : ids) {
            try {
                service.recoverExpired(id);
            } catch (Exception ex) {
                log.error("Saga recovery failed: orderId={}", id, ex);
            }
        }
    }
}

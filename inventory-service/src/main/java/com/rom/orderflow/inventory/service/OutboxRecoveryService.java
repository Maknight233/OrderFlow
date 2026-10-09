package com.rom.orderflow.inventory.service;

import com.rom.orderflow.inventory.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxRecoveryService {
    private final OutboxEventRepository repository;

    @Transactional
    public void retry(UUID eventId) {
        if (!repository.existsById(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox event not found");
        }
        if (repository.requeueFailed(eventId, Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED outbox events can be retried");
        }
    }
}

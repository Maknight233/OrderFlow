package com.rom.orderflow.inventory.controller;

import com.rom.orderflow.inventory.service.OutboxRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory/outbox")
@RequiredArgsConstructor
public class OutboxRecoveryController {
    private final OutboxRecoveryService service;

    @PostMapping("/{eventId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable UUID eventId) {
        service.retry(eventId);
    }
}

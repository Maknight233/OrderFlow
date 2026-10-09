package com.rom.orderflow.order.controller;

import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.saga.OrderSagaService;
import com.rom.orderflow.order.saga.OrderSagaService.SagaView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class SagaController {
    private final OrderSagaService service;

    @GetMapping("/{orderId}/saga")
    public SagaView get(@PathVariable UUID orderId) {
        return service.get(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderResponse cancel(@PathVariable UUID orderId,
                               @Valid @RequestBody(required = false) CancelRequest request) {
        return service.cancel(orderId, request == null ? null : request.reason());
    }

    @PostMapping("/{orderId}/saga/retry")
    public SagaView retry(@PathVariable UUID orderId) {
        return service.retry(orderId);
    }

    @PostMapping("/outbox/{eventId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryOutbox(@PathVariable UUID eventId) {
        service.retryFailedOutbox(eventId);
    }

    public record CancelRequest(@Size(max = 2000) String reason) {}
}

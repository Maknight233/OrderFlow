package com.rom.orderflow.order.controller;

import com.rom.orderflow.order.dto.CreateOrderRequest;
import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.service.OrderApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {

        OrderResponse order = orderApplicationService.createOrder(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                orderApplicationService.getOrderById(id)
        );
    }
}
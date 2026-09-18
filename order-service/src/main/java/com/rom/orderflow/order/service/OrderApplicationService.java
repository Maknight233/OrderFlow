package com.rom.orderflow.order.service;

import com.rom.orderflow.order.dto.CreateOrderRequest;
import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.error.OrderNotFoundException;
import com.rom.orderflow.order.event.OrderCreatedEvent;
import com.rom.orderflow.order.event.OrderEventPublisher;
import com.rom.orderflow.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

    public OrderApplicationService(
            OrderRepository orderRepository,
            OrderEventPublisher orderEventPublisher) {

        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {

        OrderEntity order = new OrderEntity(
                request.customerId(),
                request.totalAmount(),
                request.currency()
        );

        OrderEntity saved = orderRepository.save(order);

        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID(),
                saved.getId(),
                saved.getCustomerId(),
                saved.getStatus().name(),
                saved.getTotalAmount(),
                saved.getCurrency(),
                Instant.now()
        );

        orderEventPublisher.publishOrderCreated(event);

        return toResponse(saved);
    }

    public OrderResponse getOrderById(UUID id) {

        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new OrderNotFoundException("Order not found: " + id)
                );

        return toResponse(order);
    }

    private OrderResponse toResponse(OrderEntity order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
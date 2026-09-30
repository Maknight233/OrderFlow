package com.rom.orderflow.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rom.orderflow.order.dto.CreateOrderRequest;
import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.error.OrderNotFoundException;
import com.rom.orderflow.order.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.order.kafka.publisher.OrderEventPublisher;
import com.rom.orderflow.order.kafka.event.OrderItemEvent;
import com.rom.orderflow.order.outbox.OutboxEvent;
import com.rom.orderflow.order.outbox.OutboxStatus;
import com.rom.orderflow.order.repository.OrderRepository;
import com.rom.orderflow.order.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderApplicationService(
            OrderRepository orderRepository,
            OrderEventPublisher orderEventPublisher, OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {

        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {

        OrderEntity order = new OrderEntity(
                request.customerId(),
                request.totalAmount(),
                request.currency()
        );

        OrderEntity saved = orderRepository.save(order);

        List<OrderItemEvent> items = request.items()
                .stream()
                .map(item -> new OrderItemEvent(
                        item.sku(),
                        item.quantity()
                ))
                .toList();

        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID(),
                saved.getId(),
                saved.getCustomerId(),
                saved.getStatus().name(),
                saved.getTotalAmount(),
                saved.getCurrency(),
                items,
                Instant.now()
        );

        String payload;

        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize OrderCreatedEvent",
                    e
            );
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(event.eventId())
                .aggregateType("ORDER")
                .aggregateId(saved.getId())
                .eventType("ORDER_CREATED")
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        outboxEventRepository.save(outboxEvent);

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
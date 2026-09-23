package com.rom.orderflow.order.service;

import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.domain.OrderStatus;
import com.rom.orderflow.order.dto.CreateOrderRequest;
import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.order.kafka.publisher.OrderEventPublisher;
import com.rom.orderflow.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    private OrderApplicationService orderApplicationService;

    @BeforeEach
    void setUp() {
        orderApplicationService =
                new OrderApplicationService(
                        orderRepository,
                        orderEventPublisher
                );
    }

    @Test
    void createOrder_shouldSaveOrderAndPublishCorrectEvent() {

        UUID customerId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111114"
                );

        UUID orderId = UUID.randomUUID();

        CreateOrderRequest request =
                new CreateOrderRequest(
                        customerId,
                        new BigDecimal("99.99"),
                        "USD"
                );

        OrderEntity savedOrder = new OrderEntity();

        savedOrder.setId(orderId);
        savedOrder.setCustomerId(customerId);
        savedOrder.setStatus(OrderStatus.PENDING);
        savedOrder.setTotalAmount(new BigDecimal("99.99"));
        savedOrder.setCurrency("USD");

        when(orderRepository.save(any(OrderEntity.class)))
                .thenReturn(savedOrder);

        OrderResponse response =
                orderApplicationService.createOrder(request);

        assertNotNull(response);
        assertEquals(orderId, response.id());
        assertEquals(customerId, response.customerId());

        verify(orderRepository, times(1))
                .save(any(OrderEntity.class));

        ArgumentCaptor<OrderCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(OrderCreatedEvent.class);

        verify(orderEventPublisher, times(1))
                .publishOrderCreated(eventCaptor.capture());

        OrderCreatedEvent event = eventCaptor.getValue();

        assertEquals(orderId, event.orderId());
        assertEquals(customerId, event.customerId());
        assertEquals("PENDING", event.status());
        assertEquals(new BigDecimal("99.99"), event.totalAmount());
        assertEquals("USD", event.currency());

        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());
    }
}
package com.rom.orderflow.order.saga;

import com.rom.orderflow.order.domain.*;
import com.rom.orderflow.order.dto.OrderResponse;
import com.rom.orderflow.order.error.OrderNotFoundException;
import com.rom.orderflow.order.kafka.event.*;
import com.rom.orderflow.order.outbox.*;
import com.rom.orderflow.order.repository.*;
import com.rom.orderflow.order.observability.*;
import com.rom.orderflow.order.cache.OrderReadCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderSagaRepository sagaRepository;
    private final ProcessedInventoryEventRepository inbox;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final OutboxTracing tracing;
    private final BusinessMetrics metrics;
    private final OrderReadCache orderReadCache;

    @Value("${orderflow.saga.step-timeout-seconds:120}")
    private long timeoutSeconds;
    @Value("${orderflow.saga.max-retries:5}")
    private int maxRetries;

    @Transactional
    public void start(UUID orderId, List<OrderItemEvent> items) {
        OrderSaga saga = new OrderSaga();
        saga.setOrderId(orderId);
        transition(saga, SagaState.WAITING_RESERVATION);
        sagaRepository.save(saga);
        enqueue(WorkflowEvent.create(orderId, "ORDER_CREATED", items, null));
    }

    @Transactional
    public void handle(WorkflowEvent event) {
        event.validate();
        String type = event.resolvedType();
        if (!Set.of("INVENTORY_RESERVED", "INVENTORY_REJECTED",
                "INVENTORY_CONFIRMED", "INVENTORY_RELEASED").contains(type)) {
            throw new IllegalArgumentException("Unsupported inventory event: " + type);
        }
        OrderSaga saga = locked(event.orderId());
        if (inbox.existsById(event.eventId())) return;
        OrderEntity order = order(event.orderId());
        SagaState state = saga.effectiveState();

        switch (type) {
            case "INVENTORY_RESERVED" -> {
                if (state == SagaState.WAITING_RESERVATION) {
                    order.setStatus(OrderStatus.CONFIRMED);
                    transition(saga, SagaState.WAITING_CONFIRMATION);
                    enqueue(command(saga));
                } else if (state == SagaState.COMPENSATING || state == SagaState.CANCELLED) {
                    // A release command leaves a durable tombstone at inventory.
                    enqueue(WorkflowEvent.create(order.getId(), "INVENTORY_RELEASE_REQUESTED", null,
                            saga.getFailureReason()));
                }
            }
            case "INVENTORY_REJECTED" -> {
                if (state == SagaState.WAITING_RESERVATION || state == SagaState.WAITING_CONFIRMATION) {
                    compensate(saga, order, event.reason() == null ? "Inventory rejected" : event.reason());
                }
            }
            case "INVENTORY_CONFIRMED" -> {
                if (state == SagaState.WAITING_CONFIRMATION || state == SagaState.COMPENSATING
                        || state == SagaState.WAITING_RESERVATION) {
                    // Confirmation is the point of no return. A racing cancellation loses.
                    if (state == SagaState.COMPENSATING) {
                        saga.setFailureReason("Cancellation arrived after inventory confirmation");
                    }
                    order.setStatus(OrderStatus.COMPLETED);
                    transition(saga, SagaState.COMPLETED);
                }
            }
            case "INVENTORY_RELEASED" -> {
                if (!saga.terminal()) {
                    if (saga.getFailureReason() == null && event.reason() != null) {
                        saga.setFailureReason(event.reason().substring(0, Math.min(event.reason().length(), 2000)));
                    }
                    order.setStatus(OrderStatus.CANCELLED);
                    transition(saga, SagaState.CANCELLED);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported inventory event: " + type);
        }
        inbox.save(new ProcessedInventoryEvent(event.eventId(), event.orderId(), type, Instant.now()));
        SagaState committedState = saga.getState();
        String reason = saga.getFailureReason();
        BusinessMetrics.afterCommit(() -> log.atInfo().addKeyValue("orderId", event.orderId())
                .addKeyValue("eventId", event.eventId()).addKeyValue("eventType", type)
                .addKeyValue("previousState", state).addKeyValue("state", committedState)
                .addKeyValue("reason", reason).log("Saga event committed"));
    }

    @Transactional
    public OrderResponse cancel(UUID orderId, String reason) {
        OrderSaga saga = locked(orderId);
        OrderEntity order = order(orderId);
        if (saga.getState() == SagaState.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed order cannot be cancelled");
        }
        if (saga.getState() != SagaState.CANCELLED
                && (saga.effectiveState() != SagaState.COMPENSATING
                    || saga.getState() == SagaState.MANUAL_RECOVERY)) {
            compensate(saga, order, reason == null || reason.isBlank() ? "Cancelled by client" : reason);
        }
        return OrderResponse.from(order);
    }

    @Transactional
    public void recoverExpired(UUID orderId) {
        OrderSaga saga = locked(orderId);
        if (saga.terminal() || saga.getState() == SagaState.MANUAL_RECOVERY
                || saga.getDeadline().isAfter(Instant.now())) return;
        OrderEntity order = order(orderId);
        BusinessMetrics.afterCommit(metrics::sagaTimeout);
        if (saga.getState() == SagaState.WAITING_RESERVATION) {
            compensate(saga, order, "Inventory reservation timed out");
        } else if (saga.getRetryCount() < maxRetries) {
            BusinessMetrics.afterCommit(metrics::sagaRetry);
            saga.setRetryCount(saga.getRetryCount() + 1);
            saga.setDeadline(Instant.now().plusSeconds(timeoutSeconds));
            enqueue(command(saga));
        } else if (saga.getState() == SagaState.WAITING_CONFIRMATION) {
            compensate(saga, order, "Inventory confirmation timed out");
        } else {
            saga.setRecoveryState(saga.getState());
            saga.setState(SagaState.MANUAL_RECOVERY);
            BusinessMetrics.afterCommit(() -> metrics.sagaTransition("COMPENSATING", "MANUAL_RECOVERY", null));
            log.error("Saga requires manual recovery: orderId={}, reason={}", orderId, saga.getFailureReason());
        }
    }

    @Transactional
    public SagaView retry(UUID orderId) {
        OrderSaga saga = locked(orderId);
        if (saga.getState() != SagaState.MANUAL_RECOVERY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only MANUAL_RECOVERY sagas can be retried");
        }
        SagaState previous = saga.getRecoveryState();
        if (previous == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Recovery state is missing");
        transition(saga, previous);
        saga.setUpdatedAt(Instant.now());
        enqueue(command(saga));
        return SagaView.from(saga);
    }

    @Transactional(readOnly = true)
    public SagaView get(UUID orderId) {
        return SagaView.from(sagaRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Saga not found: " + orderId)));
    }

    @Transactional
    public void retryFailedOutbox(UUID eventId) {
        OutboxEvent event = outbox.findById(eventId)
                .orElseThrow(() -> new OrderNotFoundException("Outbox event not found: " + eventId));
        OrderSaga saga = locked(event.getAggregateId());
        if (saga.terminal() || !commandType(saga.effectiveState()).equals(event.getEventType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command no longer matches the saga state");
        }
        if (outbox.requeueFailed(eventId, Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED outbox events can be retried");
        }
    }

    private void compensate(OrderSaga saga, OrderEntity order, String reason) {
        saga.setFailureReason(reason.substring(0, Math.min(reason.length(), 2000)));
        order.setStatus(OrderStatus.CANCELLING);
        transition(saga, SagaState.COMPENSATING);
        enqueue(command(saga));
    }

    private void transition(OrderSaga saga, SagaState state) {
        orderReadCache.evictAfterCommit(saga.getOrderId());
        SagaState previous = saga.getState();
        String reason = saga.getFailureReason();
        UUID orderId = saga.getOrderId();
        java.time.Duration duration = state == SagaState.COMPLETED || state == SagaState.CANCELLED
                ? java.time.Duration.between(order(orderId).getCreatedAt(), Instant.now()) : null;
        BusinessMetrics.afterCommit(() -> {
            metrics.sagaTransition(previous == null ? "NEW" : previous.name(), state.name(), duration);
            log.atInfo().addKeyValue("orderId", orderId).addKeyValue("previousState", previous)
                    .addKeyValue("state", state).addKeyValue("reason", reason).log("Saga transition committed");
        });
        saga.setState(state);
        saga.setRecoveryState(null);
        saga.setRetryCount(0);
        saga.setDeadline(Instant.now().plus(Duration.ofSeconds(timeoutSeconds)));
    }

    private WorkflowEvent command(OrderSaga saga) {
        String type = commandType(saga.effectiveState());
        List<OrderItemEvent> items = null;
        if ("ORDER_CREATED".equals(type)) {
            items = itemRepository.findByOrderIdOrderBySku(saga.getOrderId()).stream()
                    .map(item -> new OrderItemEvent(item.getSku(), item.getQuantity())).toList();
            if (items.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot reserve inventory without persisted items");
            }
        }
        return WorkflowEvent.create(saga.getOrderId(), type, items, saga.getFailureReason());
    }

    private String commandType(SagaState state) {
        return switch (state) {
            case WAITING_RESERVATION -> "ORDER_CREATED";
            case WAITING_CONFIRMATION -> "INVENTORY_CONFIRM_REQUESTED";
            case COMPENSATING -> "INVENTORY_RELEASE_REQUESTED";
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Saga has no pending command");
        };
    }

    private void enqueue(WorkflowEvent event) {
        outbox.save(OutboxEvent.builder().id(event.eventId()).aggregateType("ORDER")
                .aggregateId(event.orderId()).eventType(event.eventType())
                .traceParent(tracing.capture()).payload(objectMapper.writeValueAsString(event)).status(OutboxStatus.PENDING).build());
    }

    private OrderSaga locked(UUID orderId) {
        return sagaRepository.findForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Saga not found: " + orderId));
    }

    private OrderEntity order(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
    }

    public record SagaView(UUID orderId, SagaState state, SagaState recoveryState,
                           String failureReason, Instant deadline, int retryCount, Instant updatedAt) {
        static SagaView from(OrderSaga saga) {
            return new SagaView(saga.getOrderId(), saga.getState(), saga.getRecoveryState(),
                    saga.getFailureReason(), saga.getDeadline(), saga.getRetryCount(), saga.getUpdatedAt());
        }
    }
}

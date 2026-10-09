package com.rom.orderflow.inventory.workflow;

import com.rom.orderflow.inventory.domain.*;
import com.rom.orderflow.inventory.dto.ReserveInventoryRequest;
import com.rom.orderflow.inventory.kafka.event.*;
import com.rom.orderflow.inventory.outbox.*;
import com.rom.orderflow.inventory.repository.*;
import com.rom.orderflow.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class InventoryWorkflowTransactions {
    private final InventoryWorkflowLock workflowLock;
    private final InventoryService inventoryService;
    private final InventoryReservationRepository reservations;
    private final ProcessedEventRepository inbox;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    @Transactional
    public void reserve(WorkflowEvent event) {
        InventoryWorkflow workflow = workflowLock.lock(event.orderId());
        if (inbox.existsById(event.eventId())) return;
        if (workflow.getState() == InventoryWorkflowState.NEW) {
            for (OrderItemEvent item : event.items().stream()
                    .sorted(Comparator.comparing(OrderItemEvent::sku)).toList()) {
                inventoryService.reserve(new ReserveInventoryRequest(
                        event.orderId(), item.sku(), item.quantity()));
            }
            workflow.setState(InventoryWorkflowState.RESERVED);
        }
        complete(event, workflow);
    }

    @Transactional
    public void reject(WorkflowEvent event, String reason) {
        InventoryWorkflow workflow = workflowLock.lock(event.orderId());
        if (inbox.existsById(event.eventId())) return;
        if (workflow.getState() == InventoryWorkflowState.NEW) {
            // Also clean up partial reservations left by pre-Day-9 consumers.
            if (reservations.findByOrderIdOrderBySku(event.orderId()).stream()
                    .anyMatch(item -> item.getStatus() == ReservationStatus.CONFIRMED)) {
                throw new IllegalStateException("Legacy confirmed reservations require manual review");
            }
            releaseReservations(event.orderId());
            workflow.setState(InventoryWorkflowState.REJECTED);
            workflow.setFailureReason(reason.substring(0, Math.min(reason.length(), 2000)));
        }
        complete(event, workflow);
    }

    @Transactional
    public void confirm(WorkflowEvent event) {
        InventoryWorkflow workflow = workflowLock.lock(event.orderId());
        if (inbox.existsById(event.eventId())) return;
        if (workflow.getState() == InventoryWorkflowState.RESERVED
                || workflow.getState() == InventoryWorkflowState.NEW) {
            List<InventoryReservation> items = reservations.findByOrderIdOrderBySku(event.orderId());
            if (items.isEmpty()) {
                // Do not record the command as processed; it may have overtaken a delayed reserve.
                throw new IllegalStateException("No inventory reservations to confirm: " + event.orderId());
            }
            boolean released = items.stream().anyMatch(item -> item.getStatus() == ReservationStatus.RELEASED);
            boolean confirmed = items.stream().anyMatch(item -> item.getStatus() == ReservationStatus.CONFIRMED);
            if (released && confirmed) {
                throw new IllegalStateException("Mixed final reservation states require manual review");
            }
            if (released) {
                releaseReservations(event.orderId());
                workflow.setState(InventoryWorkflowState.RELEASED);
                workflow.setFailureReason("A reservation was already released");
            } else {
                for (InventoryReservation item : items) {
                    inventoryService.confirm(event.orderId(), item.getSku());
                }
                workflow.setState(InventoryWorkflowState.CONFIRMED);
            }
        }
        complete(event, workflow);
    }

    @Transactional
    public void release(WorkflowEvent event) {
        InventoryWorkflow workflow = workflowLock.lock(event.orderId());
        if (inbox.existsById(event.eventId())) return;
        if (workflow.getState() != InventoryWorkflowState.CONFIRMED
                && workflow.getState() != InventoryWorkflowState.RELEASED) {
            List<InventoryReservation> items = reservations.findByOrderIdOrderBySku(event.orderId());
            if (items.stream().anyMatch(item -> item.getStatus() == ReservationStatus.CONFIRMED)) {
                if (items.stream().anyMatch(item -> item.getStatus() == ReservationStatus.RELEASED)) {
                    throw new IllegalStateException("Mixed final reservation states require manual review");
                }
                // Existing confirmed stock cannot be compensated by release.
                workflow.setState(InventoryWorkflowState.RESERVED);
                for (InventoryReservation item : items) {
                    if (item.getStatus() == ReservationStatus.RESERVED) {
                        inventoryService.confirm(event.orderId(), item.getSku());
                    }
                }
                workflow.setState(InventoryWorkflowState.CONFIRMED);
                workflow.setFailureReason("Inventory already confirmed; release cannot undo confirmation");
            } else {
                releaseReservations(event.orderId());
                workflow.setState(InventoryWorkflowState.RELEASED);
                workflow.setFailureReason(event.reason() == null ? null
                        : event.reason().substring(0, Math.min(event.reason().length(), 2000)));
            }
        }
        // Even an empty release persists RELEASED, fencing later reserve commands.
        complete(event, workflow);
    }

    private void releaseReservations(UUID orderId) {
        for (InventoryReservation item : reservations.findByOrderIdOrderBySku(orderId)) {
            if (item.getStatus() == ReservationStatus.RESERVED) {
                inventoryService.release(orderId, item.getSku());
            }
        }
    }

    private void complete(WorkflowEvent command, InventoryWorkflow workflow) {
        String type = switch (workflow.getState()) {
            case RESERVED -> "INVENTORY_RESERVED";
            case REJECTED -> "INVENTORY_REJECTED";
            case CONFIRMED -> "INVENTORY_CONFIRMED";
            case RELEASED -> "INVENTORY_RELEASED";
            case NEW -> throw new IllegalStateException("Inventory command did not reach a result");
        };
        WorkflowEvent result = WorkflowEvent.create(command.orderId(), type, null, workflow.getFailureReason());
        outbox.save(OutboxEvent.builder().id(result.eventId()).aggregateType("INVENTORY")
                .aggregateId(result.orderId()).eventType(type)
                .payload(objectMapper.writeValueAsString(result)).status(OutboxStatus.PENDING).build());
        inbox.save(ProcessedEvent.builder().eventId(command.eventId()).eventType(command.resolvedType())
                .processedAt(LocalDateTime.now()).build());
    }
}

package com.rom.orderflow.inventory.cache;

import com.rom.orderflow.inventory.domain.*;
import com.rom.orderflow.inventory.dto.*;
import com.rom.orderflow.inventory.repository.*;
import com.rom.orderflow.inventory.service.InventoryService;
import com.rom.orderflow.inventory.workflow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

/** HTTP mutations are also the shared stock mutations called by Kafka Saga transactions. */
class InventoryCacheInvalidationTest {
    private InventoryRepository inventory;
    private InventoryReservationRepository reservations;
    private InventoryWorkflowLock workflowLock;
    private InventoryReadCache cache;
    private InventoryService service;
    private Inventory row;
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach void setup() {
        inventory = mock(InventoryRepository.class);
        reservations = mock(InventoryReservationRepository.class);
        workflowLock = mock(InventoryWorkflowLock.class);
        cache = mock(InventoryReadCache.class);
        service = new InventoryService(inventory, reservations, workflowLock, cache);
        row = new Inventory();
        row.setSku("SKU");
        row.setQuantity(10);
        row.setReservedQuantity(2);
        when(inventory.findBySku("SKU")).thenReturn(Optional.of(row));
        when(inventory.save(any())).thenAnswer(call -> call.getArgument(0));
        when(inventory.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        InventoryWorkflow workflow = new InventoryWorkflow();
        workflow.setState(InventoryWorkflowState.NEW);
        when(workflowLock.lock(orderId)).thenReturn(workflow);
    }

    @Test void createInvalidates() {
        service.createInventory(new CreateInventoryRequest("SKU", "Product", 10));
        verify(cache).evictAfterCommit("SKU");
    }

    @Test void updateInvalidates() {
        service.updateInventory("SKU", new UpdateInventoryRequest(20));
        verify(cache).evictAfterCommit("SKU");
    }

    @Test void reserveInvalidates() {
        service.reserve(new ReserveInventoryRequest(orderId, "SKU", 2));
        verify(cache).evictAfterCommit("SKU");
    }

    @Test void confirmInvalidates() {
        reservation();
        service.confirm(orderId, "SKU");
        verify(cache).evictAfterCommit("SKU");
    }

    @Test void releaseInvalidates() {
        reservation();
        service.release(orderId, "SKU");
        verify(cache).evictAfterCommit("SKU");
    }

    @Test void businessReadAlwaysUsesDatabase() {
        service.getInventory("SKU");
        verify(inventory).findBySku("SKU");
        verifyNoInteractions(cache);
    }

    private void reservation() {
        InventoryReservation reservation = new InventoryReservation();
        reservation.setOrderId(orderId);
        reservation.setSku("SKU");
        reservation.setQuantity(2);
        reservation.setStatus(ReservationStatus.RESERVED);
        when(reservations.findByOrderIdAndSku(orderId, "SKU")).thenReturn(Optional.of(reservation));
    }
}

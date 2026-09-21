package com.rom.orderflow.inventory.service;

import com.rom.orderflow.inventory.domain.Inventory;
import com.rom.orderflow.inventory.domain.InventoryReservation;
import com.rom.orderflow.inventory.domain.ReservationStatus;
import com.rom.orderflow.inventory.dto.*;
import com.rom.orderflow.inventory.error.DuplicateSkuException;
import com.rom.orderflow.inventory.error.InsufficientInventoryException;
import com.rom.orderflow.inventory.error.InventoryNotFoundException;
import com.rom.orderflow.inventory.error.ReservedInventoryException;
import com.rom.orderflow.inventory.repository.InventoryRepository;
import com.rom.orderflow.inventory.repository.InventoryReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;

    @Transactional
    public InventoryResponse createInventory(CreateInventoryRequest request) {

        if (inventoryRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }

        Inventory inventory = new Inventory();

        inventory.setSku(request.sku());
        inventory.setProductName(request.productName());
        inventory.setQuantity(request.quantity());

        Inventory saved = inventoryRepository.save(inventory);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(String sku) {

        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(() ->
                        new InventoryNotFoundException(sku)
                );

        return toResponse(inventory);
    }

    @Transactional
    public InventoryResponse updateInventory(
            String sku,
            UpdateInventoryRequest request
    ) {

        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(() ->
                        new InventoryNotFoundException(sku)
                );

        inventory.setQuantity(request.quantity());

        Inventory saved = inventoryRepository.saveAndFlush(inventory);

        return toResponse(saved);
    }

    @Transactional
    public ReservationResponse reserve(
            ReserveInventoryRequest request
    ) {

        var existing = reservationRepository.findByOrderIdAndSku(
                request.orderId(),
                request.sku()
        );

        if (existing.isPresent()) {

            InventoryReservation reservation = existing.get();

            if (reservation.getQuantity().equals(request.quantity())) {
                return toReservationResponse(reservation);
            }

            throw new ReservedInventoryException("Reservation already exists with different quantity");
        }

        Inventory inventory = inventoryRepository
                .findBySku(request.sku())
                .orElseThrow(() ->
                        new InventoryNotFoundException(request.sku())
                );

        if (inventory.getQuantity() < request.quantity()) {
            throw new InsufficientInventoryException(
                    request.sku(),
                    inventory.getQuantity(),
                    request.quantity()
            );
        }

        inventory.setQuantity(
                inventory.getQuantity() - request.quantity()
        );

        inventory.setReservedQuantity(
                inventory.getReservedQuantity() + request.quantity()
        );

        InventoryReservation reservation =
                new InventoryReservation();

        reservation.setOrderId(request.orderId());
        reservation.setSku(request.sku());
        reservation.setQuantity(request.quantity());
        reservation.setStatus(ReservationStatus.RESERVED);

        reservationRepository.save(reservation);

        inventoryRepository.saveAndFlush(inventory);

        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getSku(),
                reservation.getQuantity(),
                reservation.getStatus().name()
        );
    }

    private InventoryResponse toResponse(Inventory inventory) {

        return new InventoryResponse(
                inventory.getId(),
                inventory.getSku(),
                inventory.getProductName(),
                inventory.getQuantity(),
                inventory.getVersion(),
                inventory.getCreatedAt(),
                inventory.getUpdatedAt()
        );
    }

    @Transactional
    public ReservationResponse confirm(UUID orderId, String sku) {

        InventoryReservation reservation =
                reservationRepository.findByOrderIdAndSku(orderId, sku)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Reservation not found for order: "
                                                + orderId + ", sku: " + sku
                                )
                        );

        // 支持重复请求，后面 Kafka retry 很重要
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return toReservationResponse(reservation);
        }

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new IllegalStateException(
                    "Released reservation cannot be confirmed"
            );
        }

        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(() ->
                        new InventoryNotFoundException(sku)
                );

        inventory.setReservedQuantity(
                inventory.getReservedQuantity()
                        - reservation.getQuantity()
        );

        reservation.setStatus(ReservationStatus.CONFIRMED);

        inventoryRepository.saveAndFlush(inventory);
        reservationRepository.save(reservation);

        return toReservationResponse(reservation);
    }

    @Transactional
    public ReservationResponse release(UUID orderId, String sku) {

        InventoryReservation reservation =
                reservationRepository.findByOrderIdAndSku(orderId, sku)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Reservation not found for order: "
                                                + orderId + ", sku: " + sku
                                )
                        );

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return toReservationResponse(reservation);
        }

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Confirmed reservation cannot be released"
            );
        }

        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(() ->
                        new InventoryNotFoundException(sku)
                );

        inventory.setQuantity(
                inventory.getQuantity()
                        + reservation.getQuantity()
        );

        inventory.setReservedQuantity(
                inventory.getReservedQuantity()
                        - reservation.getQuantity()
        );

        reservation.setStatus(ReservationStatus.RELEASED);

        inventoryRepository.saveAndFlush(inventory);
        reservationRepository.save(reservation);

        return toReservationResponse(reservation);
    }

    private ReservationResponse toReservationResponse(
            InventoryReservation reservation
    ) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getSku(),
                reservation.getQuantity(),
                reservation.getStatus().name()
        );
    }
}

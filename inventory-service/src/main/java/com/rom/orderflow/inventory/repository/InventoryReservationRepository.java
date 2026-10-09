package com.rom.orderflow.inventory.repository;

import com.rom.orderflow.inventory.domain.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface InventoryReservationRepository
        extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdOrderBySku(UUID orderId);

    Optional<InventoryReservation> findByOrderIdAndSku(
            UUID orderId,
            String sku
    );

    boolean existsByOrderIdAndSku(
            UUID orderId,
            String sku
    );
}

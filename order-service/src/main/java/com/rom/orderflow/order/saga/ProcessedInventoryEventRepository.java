package com.rom.orderflow.order.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProcessedInventoryEventRepository extends JpaRepository<ProcessedInventoryEvent, UUID> {
}

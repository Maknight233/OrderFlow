package com.rom.orderflow.inventory.workflow;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface InventoryWorkflowRepository extends JpaRepository<InventoryWorkflow, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from InventoryWorkflow w where w.orderId = :id")
    Optional<InventoryWorkflow> findForUpdate(@Param("id") UUID id);
}

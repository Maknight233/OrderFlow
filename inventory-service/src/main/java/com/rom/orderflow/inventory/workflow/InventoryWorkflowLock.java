package com.rom.orderflow.inventory.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class InventoryWorkflowLock {
    private final JdbcTemplate jdbc;
    private final InventoryWorkflowRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryWorkflow lock(UUID orderId) {
        jdbc.update("""
                INSERT INTO inventory_workflows (order_id, state, updated_at, version)
                VALUES (?, 'NEW', CURRENT_TIMESTAMP, 0)
                ON CONFLICT (order_id) DO NOTHING
                """, orderId);
        return repository.findForUpdate(orderId).orElseThrow();
    }
}

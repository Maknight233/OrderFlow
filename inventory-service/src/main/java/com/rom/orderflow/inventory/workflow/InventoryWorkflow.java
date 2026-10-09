package com.rom.orderflow.inventory.workflow;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_workflows")
@Getter
@Setter
@NoArgsConstructor
public class InventoryWorkflow {
    @Id
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InventoryWorkflowState state;
    @Column(length = 2000)
    private String failureReason;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    @PrePersist
    @PreUpdate
    void timestamp() {
        updatedAt = Instant.now();
    }
}

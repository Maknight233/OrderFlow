package com.rom.orderflow.order.saga;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_sagas")
@Getter
@Setter
@NoArgsConstructor
public class OrderSaga {
    @Id
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaState state;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private SagaState recoveryState;
    @Column(length = 2000)
    private String failureReason;
    @Column(nullable = false)
    private Instant deadline;
    @Column(nullable = false)
    private int retryCount;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    public SagaState effectiveState() {
        return state == SagaState.MANUAL_RECOVERY ? recoveryState : state;
    }

    public boolean terminal() {
        return state == SagaState.COMPLETED || state == SagaState.CANCELLED;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }
}

package com.rom.orderflow.order.saga;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_inventory_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedInventoryEvent {
    @Id
    private UUID eventId;
    @Column(nullable = false)
    private UUID orderId;
    @Column(nullable = false)
    private String eventType;
    @Column(nullable = false)
    private Instant processedAt;
}

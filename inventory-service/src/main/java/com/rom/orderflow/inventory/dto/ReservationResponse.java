package com.rom.orderflow.inventory.dto;

import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        String sku,
        Integer quantity,
        String status
) {
}
package com.rom.orderflow.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveInventoryRequest(

        @NotNull
        UUID orderId,

        @NotBlank
        String sku,

        @NotNull
        @Min(1)
        Integer quantity

) {
}
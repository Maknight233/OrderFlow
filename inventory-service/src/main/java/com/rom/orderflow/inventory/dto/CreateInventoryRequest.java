package com.rom.orderflow.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateInventoryRequest(

        @NotBlank
        String sku,

        @NotBlank
        String productName,

        @NotNull
        @Min(0)
        Integer quantity

) {
}
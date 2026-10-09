package com.rom.orderflow.order.dto;
import jakarta.validation.constraints.*;

public record OrderItemRequest(
        @NotBlank @Size(max = 100) String sku,
        @Min(1) int quantity
) {
}

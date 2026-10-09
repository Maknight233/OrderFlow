package com.rom.orderflow.order.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotNull @DecimalMin("0.0") BigDecimal totalAmount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotEmpty List<@NotNull @Valid OrderItemRequest> items
) {
}

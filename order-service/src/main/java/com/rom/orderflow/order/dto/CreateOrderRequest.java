package com.rom.orderflow.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderRequest(

        @NotNull
        UUID customerId,

        @NotNull
        @Positive
        BigDecimal totalAmount,

        @NotBlank
        @Size(min = 3, max = 3)
        String currency

) {
}
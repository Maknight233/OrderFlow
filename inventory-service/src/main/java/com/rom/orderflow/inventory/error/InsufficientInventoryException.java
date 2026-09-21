package com.rom.orderflow.inventory.error;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InsufficientInventoryException
        extends RuntimeException {

    public InsufficientInventoryException(
            String sku,
            int available,
            int requested
    ) {
        super(
                "Insufficient inventory for SKU " + sku
                        + ". Available: " + available
                        + ", requested: " + requested
        );
    }
}

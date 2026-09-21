package com.rom.orderflow.inventory.error;

import java.util.UUID;

public class ReservedInventoryException extends RuntimeException {
    public ReservedInventoryException(String message) {
        super(message);
    }
}

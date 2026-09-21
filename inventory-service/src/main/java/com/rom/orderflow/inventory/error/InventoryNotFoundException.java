package com.rom.orderflow.inventory.error;

public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(String sku) {
        super("Inventory not found for SKU: " + sku);
    }
}

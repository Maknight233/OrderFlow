package com.rom.orderflow.inventory.error;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("Inventory already exists for SKU: " + sku);
    }
}
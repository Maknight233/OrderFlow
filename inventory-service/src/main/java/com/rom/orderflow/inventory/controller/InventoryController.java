package com.rom.orderflow.inventory.controller;

import com.rom.orderflow.inventory.dto.*;
import com.rom.orderflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryResponse createInventory(
            @Valid @RequestBody CreateInventoryRequest request
    ) {
        return inventoryService.createInventory(request);
    }

    @GetMapping("/{sku}")
    public InventoryResponse getInventory(
            @PathVariable String sku
    ) {
        return inventoryService.getInventory(sku);
    }

    @PutMapping("/{sku}")
    public InventoryResponse updateInventory(
            @PathVariable String sku,
            @Valid @RequestBody UpdateInventoryRequest request
    ) {
        return inventoryService.updateInventory(sku, request);
    }

    @PostMapping("/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @Valid @RequestBody ReserveInventoryRequest request
    ) {
        return inventoryService.reserve(request);
    }

    @PostMapping("/reservations/{orderId}/{sku}/confirm")
    public ReservationResponse confirm(
            @PathVariable UUID orderId,
            @PathVariable String sku
    ) {
        return inventoryService.confirm(orderId, sku);
    }

    @PostMapping("/reservations/{orderId}/{sku}/release")
    public ReservationResponse release(
            @PathVariable UUID orderId,
            @PathVariable String sku
    ) {
        return inventoryService.release(orderId, sku);
    }
}
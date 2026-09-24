package com.rom.orderflow.inventory;

import com.rom.orderflow.inventory.domain.Inventory;
import com.rom.orderflow.inventory.dto.ReserveInventoryRequest;
import com.rom.orderflow.inventory.repository.InventoryRepository;
import com.rom.orderflow.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class InventoryReleaseIdempotencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void releaseTwiceShouldNotIncreaseInventoryTwice() {

        String sku = "IPHONE17PRO-256-BLK";
        UUID orderId = UUID.randomUUID();

        // 1. 记录测试开始前库存
        Inventory before = inventoryRepository.findBySku(sku)
                .orElseThrow();

        int originalQuantity = before.getQuantity();
        int originalReservedQuantity = before.getReservedQuantity();

        System.out.println(
                "Before reserve: quantity="
                        + originalQuantity
                        + ", reserved="
                        + originalReservedQuantity
        );

        // 2. Reserve 1 个
        ReserveInventoryRequest request =
                new ReserveInventoryRequest(
                        orderId,
                        sku,
                        1
                );

        inventoryService.reserve(request);

        Inventory afterReserve =
                inventoryRepository.findBySku(sku)
                        .orElseThrow();

        System.out.println(
                "After reserve: quantity="
                        + afterReserve.getQuantity()
                        + ", reserved="
                        + afterReserve.getReservedQuantity()
        );

        assertEquals(
                originalQuantity - 1,
                afterReserve.getQuantity()
        );

        assertEquals(
                originalReservedQuantity + 1,
                afterReserve.getReservedQuantity()
        );

        // 3. 第一次 release
        inventoryService.release(orderId, sku);

        Inventory afterFirstRelease =
                inventoryRepository.findBySku(sku)
                        .orElseThrow();

        System.out.println(
                "After first release: quantity="
                        + afterFirstRelease.getQuantity()
                        + ", reserved="
                        + afterFirstRelease.getReservedQuantity()
        );

        assertEquals(
                originalQuantity,
                afterFirstRelease.getQuantity()
        );

        assertEquals(
                originalReservedQuantity,
                afterFirstRelease.getReservedQuantity()
        );

        // 4. 第二次 release
        inventoryService.release(orderId, sku);

        Inventory afterSecondRelease =
                inventoryRepository.findBySku(sku)
                        .orElseThrow();

        System.out.println(
                "After second release: quantity="
                        + afterSecondRelease.getQuantity()
                        + ", reserved="
                        + afterSecondRelease.getReservedQuantity()
        );

        // 5. 第二次 release 后库存必须完全不变
        assertEquals(
                afterFirstRelease.getQuantity(),
                afterSecondRelease.getQuantity()
        );

        assertEquals(
                afterFirstRelease.getReservedQuantity(),
                afterSecondRelease.getReservedQuantity()
        );
    }
}
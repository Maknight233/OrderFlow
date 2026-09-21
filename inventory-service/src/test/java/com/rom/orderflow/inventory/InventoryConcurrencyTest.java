package com.rom.orderflow.inventory;

import com.rom.orderflow.inventory.domain.Inventory;
import com.rom.orderflow.inventory.dto.ReserveInventoryRequest;
import com.rom.orderflow.inventory.repository.InventoryRepository;
import com.rom.orderflow.inventory.repository.InventoryReservationRepository;
import com.rom.orderflow.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InventoryConcurrencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @BeforeEach
    void setup() {

        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();

        Inventory inventory = new Inventory();

        inventory.setSku("TEST-LAST-ONE");
        inventory.setProductName("Concurrency Test Product");
        inventory.setQuantity(1);
        inventory.setReservedQuantity(0);

        inventoryRepository.saveAndFlush(inventory);
    }

    @Test
    void onlyOneOrderShouldReserveLastItem() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        ReserveInventoryRequest request1 =
                new ReserveInventoryRequest(
                        UUID.randomUUID(),
                        "TEST-LAST-ONE",
                        1
                );

        ReserveInventoryRequest request2 =
                new ReserveInventoryRequest(
                        UUID.randomUUID(),
                        "TEST-LAST-ONE",
                        1
                );

        Future<Boolean> result1 = executor.submit(() -> {
            readyLatch.countDown();

            startLatch.await();

            try {
                inventoryService.reserve(request1);
                return true;
            } catch (Exception e) {
                System.out.println(
                        "Request 1 failed: "
                                + e.getClass().getSimpleName()
                                + " - "
                                + e.getMessage()
                );

                return false;
            }
        });

        Future<Boolean> result2 = executor.submit(() -> {
            readyLatch.countDown();

            startLatch.await();

            try {
                inventoryService.reserve(request2);
                return true;
            } catch (Exception e) {
                System.out.println(
                        "Request 2 failed: "
                                + e.getClass().getSimpleName()
                                + " - "
                                + e.getMessage()
                );

                return false;
            }
        });

        // 等两个线程都准备好
        readyLatch.await();

        // 同时放行
        startLatch.countDown();

        boolean success1 = result1.get();
        boolean success2 = result2.get();

        executor.shutdown();

        long successCount =
                (success1 ? 1 : 0)
                        + (success2 ? 1 : 0);

        assertThat(successCount).isEqualTo(1);

        Inventory inventory =
                inventoryRepository.findBySku("TEST-LAST-ONE")
                        .orElseThrow();

        assertThat(inventory.getQuantity()).isEqualTo(0);
        assertThat(inventory.getReservedQuantity()).isEqualTo(1);

        assertThat(reservationRepository.count()).isEqualTo(1);
    }
}
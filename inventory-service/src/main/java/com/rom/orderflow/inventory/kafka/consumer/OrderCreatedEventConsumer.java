package com.rom.orderflow.inventory.kafka.consumer;

import com.rom.orderflow.inventory.dto.ReserveInventoryRequest;
import com.rom.orderflow.inventory.error.InsufficientInventoryException;
import com.rom.orderflow.inventory.error.InventoryNotFoundException;
import com.rom.orderflow.inventory.error.ReservedInventoryException;
import com.rom.orderflow.inventory.kafka.event.InventoryResultEvent;
import com.rom.orderflow.inventory.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.inventory.kafka.event.OrderItemEvent;
import com.rom.orderflow.inventory.kafka.publisher.InventoryEventPublisher;
import com.rom.orderflow.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class OrderCreatedEventConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventPublisher eventPublisher;

    public OrderCreatedEventConsumer(
            InventoryService inventoryService,
            InventoryEventPublisher eventPublisher) {
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
            topics = "order-events",
            groupId = "inventory-service"
    )
    public void consume(OrderCreatedEvent event) {

        List<OrderItemEvent> reservedItems = new ArrayList<>();

        try {

            for (OrderItemEvent item : event.items()) {

                ReserveInventoryRequest request =
                        new ReserveInventoryRequest(
                                event.orderId(),
                                item.sku(),
                                item.quantity()
                        );

                inventoryService.reserve(request);

                // 只有 reserve 成功之后才记录
                reservedItems.add(item);

                System.out.println(
                        "Inventory reserved: orderId="
                                + event.orderId()
                                + ", sku="
                                + item.sku()
                                + ", quantity="
                                + item.quantity()
                );
            }

            InventoryResultEvent resultEvent =
                    new InventoryResultEvent(
                            UUID.randomUUID(),
                            event.orderId(),
                            "RESERVED",
                            null,
                            Instant.now()
                    );

            eventPublisher.publish(resultEvent);

            System.out.println(
                    "InventoryReservedEvent published for order: "
                            + event.orderId()
            );

        } catch (InventoryNotFoundException |
                 InsufficientInventoryException |
                 ReservedInventoryException ex) {

            System.out.println(
                    "Inventory reservation failed for order: "
                            + event.orderId()
                            + ", starting Saga compensation..."
            );

            compensate(
                    event.orderId(),
                    reservedItems
            );

            InventoryResultEvent resultEvent =
                    new InventoryResultEvent(
                            UUID.randomUUID(),
                            event.orderId(),
                            "REJECTED",
                            ex.getMessage(),
                            Instant.now()
                    );

            eventPublisher.publish(resultEvent);

            System.out.println(
                    "InventoryRejectedEvent published for order: "
                            + event.orderId()
                            + " reason=" + ex.getMessage()
            );
        }
    }

    private void compensate(
            UUID orderId,
            List<OrderItemEvent> reservedItems) {

        // Saga compensation 通常逆序执行
        List<OrderItemEvent> reverseItems =
                new ArrayList<>(reservedItems);

        Collections.reverse(reverseItems);

        for (OrderItemEvent item : reverseItems) {

            inventoryService.release(
                    orderId,
                    item.sku()
            );

            System.out.println(
                    "Saga compensation completed: orderId="
                            + orderId
                            + ", sku="
                            + item.sku()
            );
        }
    }
}
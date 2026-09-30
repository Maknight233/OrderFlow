package com.rom.orderflow.inventory.kafka.consumer;

import com.rom.orderflow.inventory.domain.ProcessedEvent;
import com.rom.orderflow.inventory.dto.ReserveInventoryRequest;
import com.rom.orderflow.inventory.error.InsufficientInventoryException;
import com.rom.orderflow.inventory.error.InventoryNotFoundException;
import com.rom.orderflow.inventory.error.ReservedInventoryException;
import com.rom.orderflow.inventory.kafka.event.InventoryResultEvent;
import com.rom.orderflow.inventory.kafka.event.OrderCreatedEvent;
import com.rom.orderflow.inventory.kafka.event.OrderItemEvent;
import com.rom.orderflow.inventory.kafka.publisher.InventoryEventPublisher;
import com.rom.orderflow.inventory.repository.ProcessedEventRepository;
import com.rom.orderflow.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class OrderCreatedEventConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventPublisher eventPublisher;
    private final ProcessedEventRepository processedEventRepository;

    public OrderCreatedEventConsumer(
            InventoryService inventoryService,
            InventoryEventPublisher eventPublisher, ProcessedEventRepository processedEventRepository) {
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(
            topics = "order-events",
            groupId = "inventory-service"
    )
    public void consume(OrderCreatedEvent event) {

        // ==============================
        // IDEMPOTENCY CHECK
        // ==============================
        if (processedEventRepository.existsById(event.eventId())) {

            System.out.println(
                    "Duplicate OrderCreatedEvent ignored: eventId="
                            + event.eventId()
                            + ", orderId="
                            + event.orderId()
            );

            return;
        }

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

            // ==============================
            // IDEMPOTENCY RECORD
            // ==============================
            markEventProcessed(event);

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

            // REJECTED 也是“已经成功处理过的事件”
            markEventProcessed(event);
        }
    }

    private void markEventProcessed(OrderCreatedEvent event) {

        ProcessedEvent processedEvent =
                ProcessedEvent.builder()
                        .eventId(event.eventId())
                        .eventType("ORDER_CREATED")
                        .processedAt(LocalDateTime.now())
                        .build();

        processedEventRepository.save(processedEvent);
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
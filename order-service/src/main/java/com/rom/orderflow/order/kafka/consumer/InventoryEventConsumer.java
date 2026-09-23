package com.rom.orderflow.order.kafka.consumer;

import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.domain.OrderStatus;
import com.rom.orderflow.order.kafka.event.InventoryResultEvent;
import com.rom.orderflow.order.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventConsumer {

    private final OrderRepository orderRepository;

    public InventoryEventConsumer(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    @KafkaListener(
            topics = "inventory-events",
            groupId = "order-service"
    )
    public void consume(InventoryResultEvent event) {

        System.out.println("==============================");
        System.out.println("InventoryResultEvent received");
        System.out.println("Order ID: " + event.orderId());
        System.out.println("Status: " + event.status());
        System.out.println("Reason: " + event.reason());

        OrderEntity order = orderRepository
                .findById(event.orderId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Order not found: " + event.orderId()
                        )
                );

        // Idempotency / state protection
        if (order.getStatus() != OrderStatus.PENDING) {
            System.out.println(
                    "Order already processed. Current status: "
                            + order.getStatus()
            );
            return;
        }

        if ("RESERVED".equals(event.status())) {

            order.setStatus(OrderStatus.CONFIRMED);

            System.out.println(
                    "Order confirmed: " + event.orderId()
            );

        } else if ("REJECTED".equals(event.status())) {

            order.setStatus(OrderStatus.CANCELLED);

            System.out.println(
                    "Order cancelled: "
                            + event.orderId()
                            + ", reason: "
                            + event.reason()
            );

        } else {

            System.out.println(
                    "Unknown inventory status: " + event.status()
            );

            return;
        }

        orderRepository.save(order);

        System.out.println(
                "New order status: " + order.getStatus()
        );

        System.out.println("==============================");
    }
}

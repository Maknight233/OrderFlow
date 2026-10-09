package com.rom.orderflow.order.repository;

import com.rom.orderflow.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByOrderIdOrderBySku(UUID orderId);
}

package com.rom.orderflow.order.service;

import com.rom.orderflow.order.domain.*;
import com.rom.orderflow.order.dto.*;
import com.rom.orderflow.order.error.OrderNotFoundException;
import com.rom.orderflow.order.kafka.event.*;
import com.rom.orderflow.order.repository.*;
import com.rom.orderflow.order.saga.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderSagaService sagaService;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Map<String, Integer> quantities = new TreeMap<>();
        for (OrderItemRequest item : request.items()) {
            quantities.merge(item.sku(), item.quantity(), Math::addExact);
        }
        OrderEntity order = orderRepository.save(new OrderEntity(
                request.customerId(), request.totalAmount(), request.currency()));
        List<OrderItem> items = quantities.entrySet().stream()
                .map(entry -> new OrderItem(order.getId(), entry.getKey(), entry.getValue())).toList();
        itemRepository.saveAll(items);
        sagaService.start(order.getId(), items.stream()
                .map(item -> new OrderItemEvent(item.getSku(), item.getQuantity())).toList());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID id) {
        return OrderResponse.from(orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id)));
    }
}

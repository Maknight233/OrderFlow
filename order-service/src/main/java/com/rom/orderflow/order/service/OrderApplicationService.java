package com.rom.orderflow.order.service;

import com.rom.orderflow.order.dto.CreateOrderRequest;
import com.rom.orderflow.order.domain.OrderEntity;
import com.rom.orderflow.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;

    public OrderApplicationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderEntity createOrder(CreateOrderRequest request) {

        OrderEntity order = new OrderEntity(
                request.customerId(),
                request.totalAmount(),
                request.currency()
        );

        return orderRepository.save(order);
    }
}
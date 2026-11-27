package com.flashorder.order.service;

import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.repository.OrderJdbcRepository;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderJdbcRepository orderJdbcRepository;

    public OrderService(OrderJdbcRepository orderJdbcRepository) {
        this.orderJdbcRepository = orderJdbcRepository;
    }

    public CreateOrderResult createOrder(CreateOrderCommand command) {
        UUID orderId = UUID.randomUUID();
        BigDecimal totalAmount = command.unitPrice().multiply(BigDecimal.valueOf(command.quantity()));
        Instant now = Instant.now();

        Order order = new Order(orderId, command.userId(), OrderStatus.PENDING, totalAmount, now);
        orderJdbcRepository.save(order);

        // Stock reservation will be decoupled via Redis + Outbox in Feature 3 & 6.
        return new CreateOrderResult(
                orderId,
                command.userId(),
                command.skuId(),
                command.quantity(),
                command.unitPrice(),
                totalAmount,
                OrderStatus.PENDING,
                null,
                null,
                now);
    }

    public Optional<OrderResult> getOrderById(UUID orderId) {
        return orderJdbcRepository.findById(orderId).map(OrderResult::from);
    }

    public OrderResult payOrder(UUID orderId) {
        Order order = orderJdbcRepository
                .findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.status() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order is not in PENDING status: " + order.status());
        }

        // Direct payment/inventory calls are detached during service extraction (Phase 2 Saga stub).
        orderJdbcRepository.updateStatus(orderId, OrderStatus.COMPLETED);

        return new OrderResult(
                order.id(), order.userId(), OrderStatus.COMPLETED, order.totalAmount(), order.createdAt());
    }
}

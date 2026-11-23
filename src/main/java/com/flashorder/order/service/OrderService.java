package com.flashorder.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.service.InventoryReservationService;
import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.repository.OrderJdbcRepository;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;

@Service
public class OrderService {
	private final OrderJdbcRepository orderJdbcRepository;
	private final InventoryReservationService inventoryReservationService;

	public OrderService(OrderJdbcRepository orderJdbcRepository,
			InventoryReservationService inventoryReservationService) {
		this.orderJdbcRepository = orderJdbcRepository;
		this.inventoryReservationService = inventoryReservationService;
	}

	public CreateOrderResult createOrder(CreateOrderCommand command) {
		// 1. UUID
		UUID orderId = UUID.randomUUID();

		// 2. Tinh tien
		BigDecimal totalAmount = command.unitPrice().multiply(BigDecimal.valueOf(command.quantity()));

		// 3. Giu kho
		ReservationResult reservation = inventoryReservationService.reserveStock(command.skuId(), orderId,
				command.quantity(), command.strategyHeader());

		// 4. Luu order
		Order order = new Order(orderId, command.userId(), OrderStatus.PENDING, totalAmount, Instant.now());
		orderJdbcRepository.save(order);

		// 5. Tra ve ket qua
		return new CreateOrderResult(orderId, command.userId(), command.skuId(), command.quantity(),
				command.unitPrice(), totalAmount, OrderStatus.PENDING, reservation.reservationId(),
				reservation.expiresAt(), Instant.now());
	}

	public Optional<OrderResult> getOrderById(UUID orderId) {
		return orderJdbcRepository.findById(orderId).map(OrderResult::from);
	}
}

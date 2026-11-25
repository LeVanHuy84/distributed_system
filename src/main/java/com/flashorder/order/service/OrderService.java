package com.flashorder.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.service.InventoryReservationService;
import com.flashorder.mock.payment.PaymentGateway;
import com.flashorder.mock.payment.PaymentResult;
import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.repository.OrderJdbcRepository;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;

@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final OrderJdbcRepository orderJdbcRepository;
	private final InventoryReservationService inventoryReservationService;
	private final PaymentGateway paymentGateway;

	public OrderService(OrderJdbcRepository orderJdbcRepository,
			InventoryReservationService inventoryReservationService, PaymentGateway paymentGateway) {
		this.orderJdbcRepository = orderJdbcRepository;
		this.inventoryReservationService = inventoryReservationService;
		this.paymentGateway = paymentGateway;
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

	// pay order
	public OrderResult payOrder(UUID orderId) {
		// 1. Kiem tra don hang
		Order order = orderJdbcRepository.findById(orderId)
				.orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

		if (order.status() != OrderStatus.PENDING) {
			throw new IllegalStateException("Order is not in PENDING status: " + order.status());
		}

		// 2. Goi cong thanh toan
		PaymentResult paymentResult = paymentGateway.processPayment(orderId, order.totalAmount());

		// 3. Xu ly khi thanh toan that bai
		if (!paymentResult.success()) {
			orderJdbcRepository.updateStatus(orderId, OrderStatus.CANCELLED);

			inventoryReservationService.releaseReservationByOrderId(orderId);

			return new OrderResult(order.id(), order.userId(), OrderStatus.CANCELLED, order.totalAmount(),
					order.createdAt());
		}

		// 4. Thanh toan thanh cong
		boolean confirm = inventoryReservationService.confirmReservation(orderId);

		if (confirm) {
			// Payment thang
			orderJdbcRepository.updateStatus(orderId, OrderStatus.COMPLETED);

			return new OrderResult(order.id(), order.userId(), OrderStatus.COMPLETED, order.totalAmount(),
					order.createdAt());
		} else {
			// Worker thang
			orderJdbcRepository.updateStatus(orderId, OrderStatus.CANCELLED);

			log.warn("Payment succeeded but reservation expired for orderId: {}. Compensating refund required.",
					orderId);

			return new OrderResult(order.id(), order.userId(), OrderStatus.CANCELLED, order.totalAmount(),
					order.createdAt());
		}
	}
}

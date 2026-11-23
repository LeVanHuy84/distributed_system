package com.flashorder.order.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;

public record OrderResult(UUID orderId, String userId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
	public static OrderResult from(Order order) {
		if (order == null) {
			return null;
		}
		return new OrderResult(order.id(), order.userId(), order.status(), order.totalAmount(), order.createdAt());
	}
}

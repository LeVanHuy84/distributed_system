package com.flashorder.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;

public record OrderResponse(UUID orderId, String userId, String skuId, int quantity, BigDecimal unitPrice,
		BigDecimal totalAmount, OrderStatus status, UUID reservationId, Instant reservationExpiresAt,
		Instant createdAt) {
	public static OrderResponse from(CreateOrderResult result) {
		if (result == null) {
			return null;
		}
		return new OrderResponse(result.orderId(), result.userId(), result.skuId(), result.quantity(),
				result.unitPrice(), result.totalAmount(), result.orderStatus(), result.reservationId(),
				result.expiresAt(), result.createdAt());
	}

	public static OrderResponse from(OrderResult result) {
		if (result == null) {
			return null;
		}
		return new OrderResponse(result.orderId(), result.userId(), null, 0, null, result.totalAmount(),
				result.status(), null, null, result.createdAt());
	}
}

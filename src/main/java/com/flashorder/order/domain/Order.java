package com.flashorder.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(UUID id, String userId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
	public Order {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("userId must be not null or blank");
		}

		if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) < 0) {
			throw new IllegalArgumentException("totalAmount must be greater than or equal 0");
		}
	}
}

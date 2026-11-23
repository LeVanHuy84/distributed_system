package com.flashorder.order.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.flashorder.order.domain.OrderStatus;

public record CreateOrderResult(UUID orderId, String userId, String skuId, int quantity, BigDecimal unitPrice,
		BigDecimal totalAmount, OrderStatus orderStatus, UUID reservationId, Instant expiresAt, Instant createdAt) {
}

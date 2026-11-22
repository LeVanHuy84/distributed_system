package com.flashorder.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record StockReservation(UUID id, UUID orderId, String skuId, int quantity, ReservationStatus status,
		Instant expiresAt, Instant createdAt) {
	public StockReservation {
		if (id == null) {
			throw new IllegalArgumentException("id must be not null");
		}

		if (orderId == null) {
			throw new IllegalArgumentException("orderId must be not null");
		}

		if (skuId == null || skuId.isBlank()) {
			throw new IllegalArgumentException("skuId must be not null or blank");
		}

		if (expiresAt == null) {
			throw new IllegalArgumentException("expiresAt must be not null");
		}

		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be greater than 0");
		}
	}

	public boolean isExpired() {
		return Instant.now().isAfter(expiresAt);
	}
}

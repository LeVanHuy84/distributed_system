package com.flashorder.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record ReservationResult(
        UUID reservationId,
        UUID orderId,
        String skuId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt) {
    public ReservationResult {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId must not be null");
        }

        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }

        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    public static ReservationResult from(StockReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }

        return new ReservationResult(
                reservation.id(),
                reservation.orderId(),
                reservation.skuId(),
                reservation.quantity(),
                reservation.status(),
                reservation.expiresAt(),
                reservation.createdAt());
    }
}

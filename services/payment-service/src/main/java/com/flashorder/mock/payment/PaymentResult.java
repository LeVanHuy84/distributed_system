package com.flashorder.mock.payment;

import java.time.Instant;
import java.util.UUID;

public record PaymentResult(UUID transactionId, UUID orderId, boolean success, String message, Instant processedAt) {
    public PaymentResult {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID cannot be null");
        }

        if (orderId == null) {
            throw new IllegalArgumentException("Order ID cannot be null");
        }
    }
}

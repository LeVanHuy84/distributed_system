package com.flashorder.order.service.dto;

import java.math.BigDecimal;

public record CreateOrderCommand(
        String userId, String skuId, int quantity, BigDecimal unitPrice, String strategyHeader) {
    public CreateOrderCommand {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }

        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId cannot be null or blank");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("unitPrice must be greater than or equal to 0");
        }
    }
}

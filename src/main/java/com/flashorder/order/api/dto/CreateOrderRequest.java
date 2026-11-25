package com.flashorder.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank(message = "userId must not be blank") String userId,

        @NotBlank(message = "skuId must not be blank") String skuId,

        @Positive(message = "quantity must be greater than 0") int quantity,

        @NotNull(message = "unitPrice must not be null") @PositiveOrZero(message = "unitPrice must be >= 0") BigDecimal unitPrice) {}

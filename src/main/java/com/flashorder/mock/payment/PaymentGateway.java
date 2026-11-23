package com.flashorder.mock.payment;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {
	PaymentResult processPayment(UUID orderId, BigDecimal amount);
}

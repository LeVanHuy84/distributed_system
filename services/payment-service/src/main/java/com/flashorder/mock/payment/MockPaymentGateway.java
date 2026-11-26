package com.flashorder.mock.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MockPaymentGateway implements PaymentGateway {

    @Value("${flashorder.mock.payment.delay-ms:0}")
    private long delayMs;

    @Override
    public PaymentResult processPayment(UUID orderId, BigDecimal amount) {

        // Simulate network latency
        if (delayMs > 0) {
            try {
                Thread.sleep(Duration.ofMillis(delayMs));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();

                return new PaymentResult(UUID.randomUUID(), orderId, false, "Payment interrupted", Instant.now());
            }
        }

        return new PaymentResult(
                UUID.randomUUID(), orderId, true, "Payment processed successfully (mock)", Instant.now());
    }
}

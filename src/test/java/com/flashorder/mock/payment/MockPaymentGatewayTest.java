package com.flashorder.mock.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class MockPaymentGatewayTest {
	private MockPaymentGateway paymentGateway;

	@BeforeEach
	void setUp() {
		paymentGateway = new MockPaymentGateway();

		ReflectionTestUtils.setField(paymentGateway, "delayMs", 0L);
	}

	@Test
	void processPayment_shouldReturnSuccessfulResult() {

		UUID orderId = UUID.randomUUID();
		BigDecimal amount = new BigDecimal("150000.00");

		PaymentResult result = paymentGateway.processPayment(orderId, amount);

		assertNotNull(result);
		assertTrue(result.success());
		assertNotNull(result.transactionId());
		assertEquals(orderId, result.orderId());
		assertNotNull(result.processedAt());
		assertEquals("Payment processed successfully (mock)", result.message());
	}

	@Test
	void processPayment_withZeroDelay_shouldCompleteUnder10ms() {
		UUID orderId = UUID.randomUUID();
		BigDecimal amount = new BigDecimal("100000");

		long startTime = System.nanoTime();

		PaymentResult result = paymentGateway.processPayment(orderId, amount);

		long elapsedNanos = System.nanoTime() - startTime;
		long elapsedMillis = elapsedNanos / 1_000_000;

		assertTrue(result.success());

		assertTrue(elapsedNanos < 10_000_000, "Expected execution under 10ms but took " + elapsedMillis + "ms");
	}

	@Test
	void processPayment_withDelay_shouldSimulateNetworkLatency() {
		ReflectionTestUtils.setField(paymentGateway, "delayMs", 50L);
		long start = System.currentTimeMillis();
		paymentGateway.processPayment(UUID.randomUUID(), BigDecimal.TEN);
		long elapsed = System.currentTimeMillis() - start;
		assertTrue(elapsed >= 45, "Should wait approximately 50ms");
	}
}

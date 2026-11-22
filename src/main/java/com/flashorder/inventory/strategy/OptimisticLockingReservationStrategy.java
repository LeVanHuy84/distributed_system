package com.flashorder.inventory.strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.InventoryItem;
import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.exception.ConcurrencyConflictException;
import com.flashorder.inventory.exception.InsufficientStockException;
import com.flashorder.inventory.exception.SkuNotFoundException;
import com.flashorder.inventory.repository.InventoryJdbcRepository;

@Component
public class OptimisticLockingReservationStrategy implements StockReservationStrategy {

	private static final int MAX_RETRIES = 3;
	private static final int INITIAL_BACKOFF_MS = 10;
	private static final long MAX_JITTER_MS = 10;
	private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

	private final InventoryJdbcRepository inventoryJdbcRepository;
	private final TransactionTemplate transactionTemplate;

	public OptimisticLockingReservationStrategy(InventoryJdbcRepository inventoryJdbcRepository,
			TransactionTemplate transactionTemplate) {
		this.inventoryJdbcRepository = inventoryJdbcRepository;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public ReservationResult reserve(String skuId, UUID orderId, int quantity) {
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				return tryReserveInTransaction(skuId, orderId, quantity);
			} catch (OptimisticLockConflictException ex) {
				if (attempt == MAX_RETRIES) {
					throw new ConcurrencyConflictException("Exceeded max retries for SKU: " + skuId);
				}

				sleepBeforeRetry(attempt);
			}
		}

		throw new ConcurrencyConflictException("Unable to reserve stock for SKU: " + skuId);
	}

	@Override
	public ConcurrencyStrategy getStrategy() {
		return ConcurrencyStrategy.OPTIMISTIC;
	}

	private ReservationResult tryReserveInTransaction(String skuId, UUID orderId, int quantity) {
		return transactionTemplate.execute(status -> {

			// 1. Find inventory
			InventoryItem inventory = inventoryJdbcRepository.findBySkuId(skuId)
					.orElseThrow(() -> new SkuNotFoundException(skuId));

			// 2. Check available stock
			if (inventory.availableStock() < quantity) {
				throw new InsufficientStockException(skuId, quantity, inventory.availableStock());
			}

			// 3. Update using optimistic lock
			int rowAffected = inventoryJdbcRepository.updateStockWithOptimisticLock(skuId, quantity,
					inventory.version());

			// 4. Create reservation
			if (rowAffected == 0) {
				throw new OptimisticLockConflictException();
			}

			// 5. Return ReservationResult
			Instant now = Instant.now();
			Instant expiresAt = now.plus(RESERVATION_TTL);

			StockReservation reservation = new StockReservation(UUID.randomUUID(), orderId, skuId, quantity,
					ReservationStatus.RESERVED, expiresAt, now);

			inventoryJdbcRepository.createReservation(reservation);

			return ReservationResult.from(reservation);
		});
	}

	private void sleepBeforeRetry(int attempt) {
		long exponentialBackoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));

		long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_MS + 1);

		long sleepTime = exponentialBackoff + jitter;

		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();

			throw new ConcurrencyConflictException("Reservation retry interrupted");
		}
	}

	private static class OptimisticLockConflictException extends RuntimeException {
	}
}

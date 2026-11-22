package com.flashorder.inventory.strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
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
public class PessimisticLockingReservationStrategy implements StockReservationStrategy {

	private static final int LOCK_TIMEOUT_MS = 2000;
	private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

	private final InventoryJdbcRepository inventoryJdbcRepository;
	private final TransactionTemplate transactionTemplate;

	public PessimisticLockingReservationStrategy(InventoryJdbcRepository inventoryJdbcRepository,
			TransactionTemplate transactionTemplate) {
		this.inventoryJdbcRepository = inventoryJdbcRepository;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public ReservationResult reserve(String skuId, UUID orderId, int quantity) {
		try {
			return tryReserveInTransaction(skuId, orderId, quantity);
		} catch (PessimisticLockingFailureException ex) {
			throw new ConcurrencyConflictException("Lock acquisition timeout for SKU " + skuId, ex);
		}
	}

	@Override
	public ConcurrencyStrategy getStrategy() {
		return ConcurrencyStrategy.PESSIMISTIC;
	}

	private ReservationResult tryReserveInTransaction(String skuId, UUID orderId, int quantity) {
		return transactionTemplate.execute(status -> {
			// 1. Set lock timeout
			inventoryJdbcRepository.setLockTimeout(LOCK_TIMEOUT_MS);

			// 2. Find inventory with pessimistic lock
			InventoryItem inventory = inventoryJdbcRepository.findBySkuIdWithPessimisticLock(skuId)
					.orElseThrow(() -> new SkuNotFoundException(skuId));

			// 3. Check available stock
			if (inventory.availableStock() < quantity) {
				throw new InsufficientStockException(skuId, quantity, inventory.availableStock());
			}

			// 4. Update reserved stock
			inventoryJdbcRepository.updateReservedStock(skuId, quantity);

			// 5. Create reservation
			Instant now = Instant.now();
			Instant expiresAt = now.plus(RESERVATION_TTL);

			StockReservation reservation = new StockReservation(UUID.randomUUID(), orderId, skuId, quantity,
					ReservationStatus.RESERVED, expiresAt, now);

			inventoryJdbcRepository.createReservation(reservation);

			// 6. Return ReservationResult
			return ReservationResult.from(reservation);
		});
	}
}

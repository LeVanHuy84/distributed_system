package com.flashorder.inventory.strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.InventoryItem;
import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.exception.InsufficientStockException;
import com.flashorder.inventory.exception.SkuNotFoundException;
import com.flashorder.inventory.repository.InventoryJdbcRepository;

@Component
public class AtomicConditionalReservationStrategy implements StockReservationStrategy {

	private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

	private final InventoryJdbcRepository inventoryJdbcRepository;
	private final TransactionTemplate transactionTemplate;

	public AtomicConditionalReservationStrategy(InventoryJdbcRepository inventoryJdbcRepository,
			TransactionTemplate transactionTemplate) {
		this.inventoryJdbcRepository = inventoryJdbcRepository;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public ReservationResult reserve(String skuId, UUID orderId, int quantity) {
		return transactionTemplate.execute(status -> {
			// Atomic update
			int rowAffected = inventoryJdbcRepository.updateStockWithAtomicCondition(skuId, quantity);

			// 2. Handle unsuccessul reservation
			if (rowAffected == 0) {
				InventoryItem inventory = inventoryJdbcRepository.findBySkuId(skuId)
						.orElseThrow(() -> new SkuNotFoundException(skuId));

				throw new InsufficientStockException(skuId, quantity, inventory.availableStock());
			}

			// 3. Create reservation
			Instant now = Instant.now();
			Instant expiresAt = now.plus(RESERVATION_TTL);

			StockReservation reservation = new StockReservation(UUID.randomUUID(), orderId, skuId, quantity,
					ReservationStatus.RESERVED, expiresAt, now);

			inventoryJdbcRepository.createReservation(reservation);

			// 4. return
			return ReservationResult.from(reservation);
		});
	}

	@Override
	public ConcurrencyStrategy getStrategy() {
		return ConcurrencyStrategy.ATOMIC_UPDATE;
	}

}

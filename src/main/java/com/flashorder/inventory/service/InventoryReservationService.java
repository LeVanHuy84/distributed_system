package com.flashorder.inventory.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.strategy.ReservationStrategyRouter;
import com.flashorder.inventory.strategy.StockReservationStrategy;

@Service
public class InventoryReservationService {

	private final ReservationStrategyRouter router;

	public InventoryReservationService(ReservationStrategyRouter router) {
		this.router = router;
	}

	public ReservationResult reserveStock(String skuId, UUID orderId, int quantity, ConcurrencyStrategy strategy) {
		StockReservationStrategy strategyImpl = router.getStrategy(strategy);
		return strategyImpl.reserve(skuId, orderId, quantity);
	}

	public ReservationResult reserveStock(String skuId, UUID orderId, int quantity, String strategyHeader) {
		StockReservationStrategy strategyImpl = router.resolve(strategyHeader);
		return strategyImpl.reserve(skuId, orderId, quantity);
	}
}

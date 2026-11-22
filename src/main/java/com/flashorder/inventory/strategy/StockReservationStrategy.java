package com.flashorder.inventory.strategy;

import java.util.UUID;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.ReservationResult;

public interface StockReservationStrategy {

	ReservationResult reserve(String skuId, UUID orderId, int quantity);

	ConcurrencyStrategy getStrategy();
}

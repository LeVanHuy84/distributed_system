package com.flashorder.inventory.strategy;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.ReservationResult;
import java.util.UUID;

public interface StockReservationStrategy {

    ReservationResult reserve(String skuId, UUID orderId, int quantity);

    ConcurrencyStrategy getStrategy();
}

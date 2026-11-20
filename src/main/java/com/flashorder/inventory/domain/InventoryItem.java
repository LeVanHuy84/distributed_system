package com.flashorder.inventory.domain;

import java.time.Instant;

public record InventoryItem(
    String skuId,
    String name,
    int totalStock,
    int reservedStock,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
    public InventoryItem {
        if (totalStock < 0) {
            throw new IllegalArgumentException("totalStock must be greater than or equal to 0");
        }

        if (reservedStock < 0) {
            throw new IllegalArgumentException("reservedStock must be greater than or equal to 0");
        }

        if (reservedStock > totalStock) {
            throw new IllegalArgumentException("reservedStock cannot exceed total stock");
        }
    }

    public int availableStock() {
        return totalStock - reservedStock;
    }

    public boolean hasAvailableStock(int quantity) {
        return quantity > 0 && availableStock() >= quantity;
    }

    public InventoryItem reserve(int quantity) {
        if(!hasAvailableStock(quantity)) {
            throw new IllegalArgumentException("Not enough available stock to reserve");
        }

        return new InventoryItem(skuId, name, totalStock, reservedStock + quantity, version, createdAt, Instant.now());
    }

    public InventoryItem releaseReservation(int quantity) {
        if (quantity <= 0 || quantity > reservedStock) {
            throw new IllegalArgumentException("Invalid release quantity");
        }

        return new InventoryItem(skuId, name, totalStock, reservedStock - quantity, version, createdAt, Instant.now());
    }
}

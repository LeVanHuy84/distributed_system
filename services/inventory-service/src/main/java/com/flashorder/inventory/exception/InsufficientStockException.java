package com.flashorder.inventory.exception;

public class InsufficientStockException extends RuntimeException {
    private final String skuId;
    private final int requestedQuantity;
    private final int availableStock;

    public InsufficientStockException(String skuId, int requestedQuantity, int availableStock) {
        super("Insufficient stock for SKU: " + skuId + ": requested=" + requestedQuantity + ", available="
                + availableStock);
        this.skuId = skuId;
        this.requestedQuantity = requestedQuantity;
        this.availableStock = availableStock;
    }

    public String getSkuId() {
        return skuId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableStock() {
        return availableStock;
    }
}

package com.flashorder.inventory.exception;

public class SkuNotFoundException extends RuntimeException {
	private final String skuId;

	public SkuNotFoundException(String skuId) {
		super("SKU not found: " + skuId);
		this.skuId = skuId;
	}

	public String getSkuId() {
		return skuId;
	}
}

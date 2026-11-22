
package com.flashorder.inventory.repository;

import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryJdbcRepository.class, TestcontainersConfiguration.class})
class InventoryJdbcRepositoryTest {

	@Autowired
	private InventoryJdbcRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tạo inventory riêng cho từng test. Không phụ thuộc vào SKU seed từ Flyway V2.
	 */
	private String createTestInventory(int totalStock) {

		String skuId = "SKU-TEST-" + UUID.randomUUID();

		jdbcTemplate.update("""
				INSERT INTO inventory_items
				    (sku_id, name, total_stock, reserved_stock, version)
				VALUES (?, ?, ?, ?, ?)
				""", skuId, "Test Product", totalStock, 0, 0);

		return skuId;
	}

	@Test
	void shouldFindInventoryItemBySkuId() {

		// Arrange
		String skuId = createTestInventory(100);

		// Act
		var result = repository.findBySkuId(skuId);

		// Assert
		assertThat(result).isPresent();

		assertThat(result.get().skuId()).isEqualTo(skuId);
		assertThat(result.get().availableStock()).isEqualTo(100);
	}

	@Test
	void shouldCreateAndRetrieveReservation() {

		// Arrange
		String skuId = createTestInventory(100);
		UUID orderId = UUID.randomUUID();

		Instant now = Instant.now();

		StockReservation reservation = new StockReservation(UUID.randomUUID(), orderId, skuId, 2,
				ReservationStatus.RESERVED, now.plusSeconds(900), now);

		// Act
		repository.createReservation(reservation);

		var result = repository.findReservationByOrderIdAndSkuId(orderId, skuId);

		// Assert
		assertThat(result).isPresent();

		assertThat(result.get().orderId()).isEqualTo(orderId);
		assertThat(result.get().skuId()).isEqualTo(skuId);
		assertThat(result.get().quantity()).isEqualTo(2);
	}

	@Test
	void shouldPreventDuplicateReservationForSameOrderAndSku() {

		// Arrange
		String skuId = createTestInventory(100);
		UUID orderId = UUID.randomUUID();

		Instant now = Instant.now();

		StockReservation firstReservation = new StockReservation(UUID.randomUUID(), orderId, skuId, 2,
				ReservationStatus.RESERVED, now.plusSeconds(900), now);

		StockReservation duplicateReservation = new StockReservation(UUID.randomUUID(), orderId, skuId, 5,
				ReservationStatus.RESERVED, now.plusSeconds(900), now);

		repository.createReservation(firstReservation);

		assertThatThrownBy(() -> repository.createReservation(duplicateReservation))
				.isInstanceOf(DuplicateKeyException.class);
	}
}

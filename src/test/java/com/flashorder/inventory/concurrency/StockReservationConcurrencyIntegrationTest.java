package com.flashorder.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashorder.TestcontainersConfiguration;
import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.InventoryItem;
import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.exception.ConcurrencyConflictException;
import com.flashorder.inventory.exception.InsufficientStockException;
import com.flashorder.inventory.repository.InventoryJdbcRepository;
import com.flashorder.inventory.service.InventoryReservationService;
import com.flashorder.support.concurrency.ConcurrentTestHelper;
import com.flashorder.support.concurrency.dto.ConcurrentExecutionResult;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockReservationConcurrencyIntegrationTest {

	@Autowired
	private InventoryReservationService reservationService;

	@Autowired
	private InventoryJdbcRepository inventoryRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("Optimistic Locking: 200 concurrent requests for 100 stock must never oversell")
	void optimisticLocking_shouldPreventOverselling_underHighContention() throws InterruptedException {

		// Given
		int initialStock = 100;
		int concurrencyLevel = 200;

		String skuId = createTestInventory(initialStock);

		// When
		ConcurrentExecutionResult<ReservationResult> result = ConcurrentTestHelper.executeConcurrently(concurrencyLevel,
				ignored -> reservationService.reserveStock(skuId, UUID.randomUUID(), 1, ConcurrencyStrategy.OPTIMISTIC),
				Duration.ofSeconds(15));

		// Then

		// 1. All requests must complete
		assertThat(result.totalCount()).as("All concurrent requests must complete").isEqualTo(concurrencyLevel);

		// 2. Successful reservations must not exceed stock
		assertThat(result.successCount()).as("Successful reservations must be between 1 and 100").isBetween(1,
				initialStock);

		// 3. Only expected exceptions are allowed
		assertThat(result.failures()).as("Only expected business/concurrency exceptions are allowed")
				.allSatisfy(exception -> assertThat(exception).isInstanceOfAny(InsufficientStockException.class,
						ConcurrencyConflictException.class));

		// 4. If stock remains, some requests must fail due to retry exhaustion
		if (result.successCount() < initialStock) {
			assertThat(result.failures()).as("Concurrency conflict must occur when stock remains")
					.anySatisfy(exception -> assertThat(exception).isInstanceOf(ConcurrencyConflictException.class));
		}

		// 5. Verify database invariants
		assertInventoryInvariants(skuId, initialStock, result);

		// 6. Version must match successful updates
		assertInventoryVersion(skuId, result.successCount());
	}

	@ParameterizedTest(name = "{0}: 200 concurrent requests for 100 stock must never oversell")
	@EnumSource(value = ConcurrencyStrategy.class, names = {"PESSIMISTIC", "ATOMIC_UPDATE"})
	void serializedStrategies_shouldPreventOverselling_underHighContention(ConcurrencyStrategy strategy)
			throws InterruptedException {
		// Given
		int initialStock = 100;
		int concurrencyLevel = 200;

		String skuId = createTestInventory(initialStock);

		// When
		ConcurrentExecutionResult<ReservationResult> result = ConcurrentTestHelper.executeConcurrently(concurrencyLevel,
				ignored -> reservationService.reserveStock(skuId, UUID.randomUUID(), 1, strategy),
				Duration.ofSeconds(15));

		// Then

		// 1. All requests must complete
		assertThat(result.totalCount()).as("All concurrent requests must complete").isEqualTo(concurrencyLevel);

		// 2. Exactly 100 requests must succeed
		assertThat(result.successCount()).as("Exactly 100 reservations must succeed").isEqualTo(initialStock);

		// 3. Exactly 100 requests must fail
		assertThat(result.failureCount()).as("Exactly 100 requests must fail")
				.isEqualTo(concurrencyLevel - initialStock);

		// 4. All failures must be caused by insufficient stock
		assertThat(result.failures()).as("All failed requests must have insufficient stock")
				.allSatisfy(exception -> assertThat(exception).isInstanceOf(InsufficientStockException.class));

		// 5. Verify common inventory invariants
		assertInventoryInvariants(skuId, initialStock, result);

		// 6. Verify version remains unchanged
		assertInventoryVersion(skuId, 0);
	}

	@ParameterizedTest
	@EnumSource(value = ConcurrencyStrategy.class, names = {"PESSIMISTIC", "ATOMIC_UPDATE"})
	void shouldPreserveInventoryWhenConcurrentRequestsReserveMultipleQuantity(ConcurrencyStrategy strategy)
			throws InterruptedException {
		int initialStock = 100;
		int concurrencyLevel = 50;
		int quantity = 3;

		String skuId = createTestInventory(initialStock);

		ConcurrentExecutionResult<ReservationResult> result = ConcurrentTestHelper.executeConcurrently(concurrencyLevel,
				ignored -> reservationService.reserveStock(skuId, UUID.randomUUID(), quantity, strategy),
				Duration.ofSeconds(15));

		assertThat(result.totalCount()).isEqualTo(concurrencyLevel);
		assertThat(result.successCount()).isEqualTo(33);
		assertThat(result.failureCount()).isEqualTo(17);

		assertFailuresAreExpected(result, List.of(InsufficientStockException.class));

		assertThat(result.successes())
				.allSatisfy(reservation -> assertThat(reservation.quantity()).isEqualTo(quantity));

		assertInventoryInvariants(skuId, initialStock, result);

		assertThat(sumReservedQuantity(skuId)).isEqualTo(99);

		// Version phụ thuộc implementation hiện tại.
		if (strategy == ConcurrencyStrategy.PESSIMISTIC || strategy == ConcurrencyStrategy.ATOMIC_UPDATE) {
			assertInventoryVersion(skuId, 0);
		}
	}

	@ParameterizedTest
	@EnumSource(ConcurrencyStrategy.class)
	void shouldRejectAllReservationsWhenStockIsZero(ConcurrencyStrategy strategy) throws InterruptedException {
		int initialStock = 0;
		int concurrencyLevel = 50;
		int quantity = 1;

		String skuId = createTestInventory(initialStock);

		ConcurrentExecutionResult<ReservationResult> result = ConcurrentTestHelper.executeConcurrently(concurrencyLevel,
				ignored -> reservationService.reserveStock(skuId, UUID.randomUUID(), quantity, strategy),
				Duration.ofSeconds(15));

		assertThat(result.totalCount()).isEqualTo(concurrencyLevel);
		assertThat(result.successCount()).isZero();
		assertThat(result.failureCount()).isEqualTo(concurrencyLevel);

		assertFailuresAreExpected(result, List.of(InsufficientStockException.class));

		assertInventoryInvariants(skuId, initialStock, result);

		assertThat(countReservations(skuId, ReservationStatus.RESERVED)).isZero();

		assertThat(sumReservedQuantity(skuId)).isZero();

		assertInventoryVersion(skuId, 0);
	}

	// ==========================================
	// Helpers
	// ==========================================

	private String createTestInventory(int totalStock) {

		if (totalStock < 0) {
			throw new IllegalArgumentException("Total stock must not be negative");
		}

		String skuId = "SKU-CONC-" + UUID.randomUUID();

		jdbcTemplate.update("""
				INSERT INTO inventory_items (
				    sku_id,
				    name,
				    total_stock,
				    reserved_stock,
				    version
				)
				VALUES (?, ?, ?, 0, 0)
				""", skuId, "Concurrency Test Product", totalStock);

		return skuId;
	}

	private int countReservations(String skuId, ReservationStatus status) {

		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM stock_reservations
				WHERE sku_id = ?
					AND status = ?
				""", Integer.class, skuId, status.name());

		return count != null ? count : 0;
	}

	private int sumReservedQuantity(String skuId) {
		Integer sum = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(quantity), 0)
				FROM stock_reservations
				WHERE sku_id = ?
					AND status = ?
				""", Integer.class, skuId, ReservationStatus.RESERVED.name());

		return sum != null ? sum : 0;
	}

	private void assertReservationResults(ConcurrentExecutionResult<ReservationResult> result) {
		List<ReservationResult> successes = result.successes();

		// Tổng quantity trả về phải bằng tổng quantity đã reserve.
		int totalSuccessfulQuantity = successes.stream().mapToInt(success -> Objects.requireNonNull(success).quantity())
				.sum();

		// Mỗi reservation phải có ID hợp lệ.
		assertThat(successes).extracting(success -> Objects.requireNonNull(success).reservationId())
				.doesNotContainNull().doesNotHaveDuplicates();

		// Mỗi order phải có ID hợp lệ và không trùng.
		assertThat(successes).extracting(success -> Objects.requireNonNull(success).orderId()).doesNotContainNull()
				.doesNotHaveDuplicates();

		assertThat(totalSuccessfulQuantity).isGreaterThanOrEqualTo(0);
	}

	private void assertInventoryInvariants(String skuId, int initialStock,
			ConcurrentExecutionResult<ReservationResult> result) {
		InventoryItem item = inventoryRepository.findBySkuId(skuId)
				.orElseThrow(() -> new AssertionError("Inventory not found: " + skuId));

		int successCount = result.successCount();

		int totalSuccessfulQuantity = result.successes().stream()
				.mapToInt(success -> Objects.requireNonNull(success).quantity()).sum();

		// 1. Inventory invariants
		assertThat(item.totalStock()).as("Total stock must remain unchanged").isEqualTo(initialStock);

		assertThat(item.reservedStock()).as("Reserved stock must match successful reservation quantities")
				.isEqualTo(totalSuccessfulQuantity);

		assertThat(item.availableStock()).as("Available stock must match remaining inventory")
				.isEqualTo(initialStock - totalSuccessfulQuantity);

		assertThat(item.reservedStock()).isLessThanOrEqualTo(item.totalStock());

		assertThat(item.availableStock()).isGreaterThanOrEqualTo(0);

		// 2. Reservation accounting invariants
		assertThat(countReservations(skuId, ReservationStatus.RESERVED))
				.as("Reservation row count must match successful requests").isEqualTo(successCount);

		assertThat(sumReservedQuantity(skuId)).as("Sum of reserved quantities must match inventory reserved stock")
				.isEqualTo(item.reservedStock());

		// 3. Reservation result invariants
		assertReservationResults(result);

		assertThat(totalSuccessfulQuantity).as("Returned successful quantities must match inventory")
				.isEqualTo(item.reservedStock());
	}

	private void assertInventoryVersion(String skuId, long expectedVersion) {

		InventoryItem item = inventoryRepository.findBySkuId(skuId)
				.orElseThrow(() -> new AssertionError("Inventory not found: " + skuId));

		assertThat(item.version()).as("Inventory version must match expected version").isEqualTo(expectedVersion);
	}

	private void assertFailuresAreExpected(ConcurrentExecutionResult<ReservationResult> result,
			List<Class<? extends Throwable>> expectedTypes) {
		assertThat(result.failures()).as("All failures must be expected business exceptions")
				.allSatisfy(exception -> assertThat(exception).isInstanceOfAny(expectedTypes.toArray(Class<?>[]::new)));
	}
}

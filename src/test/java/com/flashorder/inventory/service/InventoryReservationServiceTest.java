package com.flashorder.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.repository.InventoryJdbcRepository;
import com.flashorder.inventory.strategy.ReservationStrategyRouter;

@ExtendWith(MockitoExtension.class)
public class InventoryReservationServiceTest {

	@Mock
	private ReservationStrategyRouter router;

	@Mock
	private InventoryJdbcRepository repository;

	@Mock
	private TransactionTemplate transactionTemplate;

	@Mock
	private TransactionStatus transactionStatus;

	@InjectMocks
	private InventoryReservationService service;

	@BeforeEach
	void setUp() {
		// Cấu hình để TransactionTemplate giả lập thực thi code bên trong lambda
		when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(transactionStatus);
		});
	}

	@Test
	@DisplayName("Release reservation successfully")
	void shouldReleaseReservationSuccessfully() {
		// 1. Given
		UUID reservationId = UUID.randomUUID();
		StockReservation mockReservation = new StockReservation(reservationId, UUID.randomUUID(), "SKU-IPHONE15", 2,
				ReservationStatus.RESERVED, Instant.now().plusSeconds(600), Instant.now());

		when(repository.findReservationById(reservationId)).thenReturn(Optional.of(mockReservation));
		when(repository.updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED)).thenReturn(1);
		when(repository.releaseReservedStock("SKU-IPHONE15", 2)).thenReturn(1);

		// 2. When
		boolean result = service.releaseReservation(reservationId);

		// 3. Then
		assertThat(result).isTrue();

		verify(repository).updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED);
		verify(repository).releaseReservedStock("SKU-IPHONE15", 2);
	}

	@Test
	@DisplayName("Should not release stock twice")
	void shouldPreventDoubleRelease() {
		UUID reservationId = UUID.randomUUID();

		StockReservation reservation = new StockReservation(reservationId, UUID.randomUUID(), "SKU-IPHONE15", 2,
				ReservationStatus.RESERVED, Instant.now().plusSeconds(600), Instant.now());

		// Cả 2 lần đọc đều tìm thấy reservation.
		when(repository.findReservationById(reservationId)).thenReturn(Optional.of(reservation));

		// CAS: Lần 1 thành công, lần 2 thất bại
		when(repository.updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED)).thenReturn(1, 0);

		// Chỉ lần đầu được release stock
		when(repository.releaseReservedStock("SKU-IPHONE15", 2)).thenReturn(1);

		// When
		boolean firstResult = service.releaseReservation(reservationId);
		boolean secondResult = service.releaseReservation(reservationId);

		// Then
		assertThat(firstResult).isTrue();
		assertThat(secondResult).isFalse();

		// CAS được thử 2 lần
		verify(repository, Mockito.times(2)).updateReservationStatusWithCondition(reservationId,
				ReservationStatus.RESERVED, ReservationStatus.RELEASED);

		// Nhưng stock chỉ release 1 lần
		verify(repository, Mockito.times(1)).releaseReservedStock("SKU-IPHONE15", 2);
	}

	@Test
	@DisplayName("Should reject release when reservation is not RESERVED")
	void shouldRejectReleaseWhenReservationStatusIsInvalid() {
		UUID reservationId = UUID.randomUUID();

		StockReservation confirmedReservation = new StockReservation(reservationId, UUID.randomUUID(), "SKU-IPHONE15",
				2, ReservationStatus.CONFIRMED, Instant.now().plusSeconds(600), Instant.now());

		// Given
		when(repository.findReservationById(reservationId)).thenReturn(Optional.of(confirmedReservation));

		// CAS không thành công vì trạng thái DB không phải RESERVED
		when(repository.updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED)).thenReturn(0);

		// When
		boolean result = service.releaseReservation(reservationId);

		// Then
		assertThat(result).isFalse();

		// Service có gọi CAS để thử chuyển trạng thái
		verify(repository).updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED);

		// CAS thất bại nên tuyệt đối không được release stock
		verify(repository, Mockito.never()).releaseReservedStock(Mockito.anyString(), Mockito.anyInt());
	}

	@Test
	@DisplayName("Should rollback when stock release fails")
	void shouldRollbackWhenStockReleaseFails() {
		UUID reservationId = UUID.randomUUID();

		StockReservation reservation = new StockReservation(reservationId, UUID.randomUUID(), "SKU-IPHONE15", 2,
				ReservationStatus.RESERVED, Instant.now().plusSeconds(600), Instant.now());

		// Given
		when(repository.findReservationById(reservationId)).thenReturn(Optional.of(reservation));

		when(repository.updateReservationStatusWithCondition(reservationId, ReservationStatus.RESERVED,
				ReservationStatus.RELEASED)).thenReturn(1);

		when(repository.releaseReservedStock("SKU-IPHONE15", 2)).thenReturn(0);

		// When
		boolean result = service.releaseReservation(reservationId);

		// Then
		assertThat(result).isFalse();

		verify(transactionStatus).setRollbackOnly();

		verify(repository).releaseReservedStock("SKU-IPHONE15", 2);
	}
}

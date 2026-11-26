package com.flashorder.inventory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.repository.InventoryJdbcRepository;
import com.flashorder.inventory.service.InventoryReservationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ReservationReleaseWorkerTest {

    @Mock
    private InventoryJdbcRepository repository;

    @Mock
    private InventoryReservationService service;

    @InjectMocks
    private ReservationReleaseWorker worker;

    // Test 1 - Khong co don het hạn
    @Test
    public void shouldReturnZeroWhenNoExpiredReservations() {
        // Given
        when(repository.findExpiredReservations(any(Instant.class), anyInt())).thenReturn(List.of());

        // when
        int count = worker.processExpiredReservations();

        // then
        assertThat(count).isEqualTo(0);
        verify(service, never()).releaseReservation(any());
    }

    // Test 2 - Xu ly thanh cong nhieu don
    @Test
    public void shouldReleaseAllExpiredReservationsSuccessfully() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        StockReservation reservation1 = createDummyReservation(id1);
        StockReservation reservation2 = createDummyReservation(id2);

        // Given
        when(repository.findExpiredReservations(any(), anyInt())).thenReturn(List.of(reservation1, reservation2));
        when(service.releaseReservation(id1)).thenReturn(true);
        when(service.releaseReservation(id2)).thenReturn(true);

        // When
        int count = worker.processExpiredReservations();

        // then
        assertThat(count).isEqualTo(2);
        verify(service).releaseReservation(id1);
        verify(service).releaseReservation(id2);
    }

    // Test 3 - Co lap loi
    @Test
    public void shouldContinueProcessingWhenOneReservationThrowsException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        StockReservation reservation1 = createDummyReservation(id1);
        StockReservation reservation2 = createDummyReservation(id2);

        // Given
        when(repository.findExpiredReservations(any(), anyInt())).thenReturn(List.of(reservation1, reservation2));
        when(service.releaseReservation(id1)).thenThrow(new RuntimeException("Simulated DB timeout"));
        when(service.releaseReservation(id2)).thenReturn(true);

        // when
        int count = worker.processExpiredReservations();

        // then
        assertThat(count).isEqualTo(1);
        verify(service).releaseReservation(id2);
    }

    // Test 4 - Bo qua don da xu ly
    @Test
    public void shouldNotCountWhenReleaseReservationReturnsFalse() {
        UUID id1 = UUID.randomUUID();

        StockReservation reservation1 = createDummyReservation(id1);

        // Given
        when(repository.findExpiredReservations(any(), anyInt())).thenReturn(List.of(reservation1));
        when(service.releaseReservation(id1)).thenReturn(false);

        // when
        int count = worker.processExpiredReservations();

        // then
        assertThat(count).isEqualTo(0);
        verify(service).releaseReservation(id1);
    }

    // Helper
    private StockReservation createDummyReservation(UUID id) {
        return new StockReservation(
                id,
                UUID.randomUUID(),
                "SKU-TEST-001",
                2,
                ReservationStatus.RESERVED,
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(600));
    }
}

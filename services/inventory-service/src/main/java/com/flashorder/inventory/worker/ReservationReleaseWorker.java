package com.flashorder.inventory.worker;

import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.repository.InventoryJdbcRepository;
import com.flashorder.inventory.service.InventoryReservationService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationReleaseWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationReleaseWorker.class);

    private static final int BATCH_SIZE = 100;

    private final InventoryJdbcRepository repository;
    private final InventoryReservationService inventoryReservationService;

    public ReservationReleaseWorker(
            InventoryJdbcRepository repository, InventoryReservationService inventoryReservationService) {
        this.repository = repository;
        this.inventoryReservationService = inventoryReservationService;
    }

    @Scheduled(
            fixedDelayString = "${flashorder.worker.release-interval-ms:5000}",
            initialDelayString = "${flashorder.worker.release-initial-delay-ms:5000}")
    public void runScheduledRelease() {
        int count = processExpiredReservations();
        if (count > 0) {
            log.info("Successfully released {} expired reservations", count);
        }
    }

    public int processExpiredReservations() {
        Instant now = Instant.now();
        List<StockReservation> expiredList = repository.findExpiredReservations(now, BATCH_SIZE);

        if (expiredList.isEmpty()) {
            return 0;
        }

        int releasedCount = 0;
        for (StockReservation reservation : expiredList) {
            try {
                boolean success = inventoryReservationService.releaseReservation(reservation.id());
                if (success) {
                    releasedCount++;
                }
            } catch (Exception ex) {
                log.error("Error releasing reservation {}", reservation.id(), ex);
            }
        }

        return releasedCount;
    }
}

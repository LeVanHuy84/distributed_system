package com.flashorder.inventory.service;

import com.flashorder.inventory.domain.ConcurrencyStrategy;
import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;
import com.flashorder.inventory.repository.InventoryJdbcRepository;
import com.flashorder.inventory.strategy.ReservationStrategyRouter;
import com.flashorder.inventory.strategy.StockReservationStrategy;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);

    private final ReservationStrategyRouter router;
    private final InventoryJdbcRepository repository;
    private final TransactionTemplate transactionTemplate;

    public InventoryReservationService(
            ReservationStrategyRouter router,
            InventoryJdbcRepository repository,
            TransactionTemplate transactionTemplate) {
        this.router = router;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    public ReservationResult reserveStock(String skuId, UUID orderId, int quantity, ConcurrencyStrategy strategy) {
        StockReservationStrategy strategyImpl = router.getStrategy(strategy);
        return strategyImpl.reserve(skuId, orderId, quantity);
    }

    public ReservationResult reserveStock(String skuId, UUID orderId, int quantity, String strategyHeader) {
        StockReservationStrategy strategyImpl = router.resolve(strategyHeader);
        return strategyImpl.reserve(skuId, orderId, quantity);
    }

    public boolean releaseReservation(UUID reservationId) {
        if (reservationId == null) {
            return false;
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 1. Tìm reservation
            Optional<StockReservation> reservationOpt = repository.findReservationById(reservationId);

            if (reservationOpt.isEmpty()) {
                log.debug("Reservation not found for id: {}", reservationId);

                return false;
            }

            StockReservation reservation = reservationOpt.get();

            // 2. CAS
            int updatedRows = repository.updateReservationStatusWithCondition(
                    reservationId, ReservationStatus.RESERVED, ReservationStatus.RELEASED);

            if (updatedRows == 0) {
                log.debug("Reservation {} is already processed", reservationId);

                return false;
            }

            // 3. Hoan tra reserved_stock cho bang inventory_items
            int stockUpdatedRows = repository.releaseReservedStock(reservation.skuId(), reservation.quantity());

            // 4. Kiem tra toan ven du lieu
            if (stockUpdatedRows == 0) {
                log.error(
                        "Failed to release reserved stock. reservationId={}, skuId={}, quantity={}",
                        reservationId,
                        reservation.skuId(),
                        reservation.quantity());

                status.setRollbackOnly();

                return false;
            }

            return true;
        }));
    }

    // Confirm reservation
    public boolean confirmReservation(UUID orderId) {
        if (orderId == null) {
            return false;
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Optional<StockReservation> reservationOpt = repository.findReservationByOrderId(orderId);

            if (reservationOpt.isEmpty()) {
                log.warn("Reservation not found.");
                return false;
            }

            StockReservation reservation = reservationOpt.get();

            // update
            int updateRows = repository.updateReservationStatusWithCondition(
                    reservation.id(), ReservationStatus.RESERVED, ReservationStatus.CONFIRMED);

            if (updateRows == 0) {
                log.debug("The reservation has been released by the worker.");

                return false;
            }

            int stockUpdated = repository.confirmDeductStock(reservation.skuId(), reservation.quantity());

            if (stockUpdated == 0) {
                status.setRollbackOnly();
                return false;
            }

            return true;
        }));
    }

    // Helper
    public boolean releaseReservationByOrderId(UUID orderId) {
        return repository
                .findReservationByOrderId(orderId)
                .map(reservation -> releaseReservation(reservation.id()))
                .orElse(false);
    }
}

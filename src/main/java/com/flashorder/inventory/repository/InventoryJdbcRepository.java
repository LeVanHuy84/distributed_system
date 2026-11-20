package com.flashorder.inventory.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.flashorder.inventory.domain.InventoryItem;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.domain.StockReservation;

@Repository 
public class InventoryJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    public InventoryJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<InventoryItem> INVENTORY_ROW_MAPPER = 
        (rs, rowNum) -> new InventoryItem(
            rs.getString("sku_id"),
            rs.getString("name"),
            rs.getInt("total_stock"),
            rs.getInt("reserved_stock"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );

    private static final RowMapper<StockReservation> RESERVATION_ROW_MAPPER =
        (rs, rowNum) -> new StockReservation(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getString("sku_id"),
            rs.getInt("quantity"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
        );

    // 1. Tìm InventoryItem theo skuId
    public Optional<InventoryItem> findBySkuId(String skuId) {
        String sql = """
            SELECT sku_id, name, total_stock, reserved_stock, version, created_at, updated_at
            FROM inventory_items
            WHERE sku_id = ?
            """;
        
        return jdbcTemplate.query(sql, INVENTORY_ROW_MAPPER, skuId)
            .stream()
            .findFirst();
    }

    // 2. Tạo mới bản ghi giữ hàng
    public void createReservation(StockReservation reservation) {
        String sql = """
            INSERT INTO stock_reservations (id, order_id, sku_id, quantity, status, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
            reservation.id(),
            reservation.orderId(),
            reservation.skuId(),
            reservation.quantity(),
            reservation.status().name(),
            Timestamp.from(reservation.expiresAt()),
            Timestamp.from(reservation.createdAt())
        );
    }

    // 3. Tìm Reservation theo ID
    public Optional<StockReservation> findReservationById(UUID id) {
        String sql = """
            SELECT id, order_id, sku_id, quantity, status, expires_at, created_at
            FROM stock_reservations
            WHERE id = ?
            """;

        return jdbcTemplate.query(sql, RESERVATION_ROW_MAPPER, id)
            .stream()
            .findFirst();
    }

    // 4. Tìm Reservation theo OrderId và SkuId
    public Optional<StockReservation> findReservationByOrderIdAndSkuId(UUID orderId, String skuId) {
        String sql = """
            SELECT id, order_id, sku_id, quantity, status, expires_at, created_at
            FROM stock_reservations
            WHERE order_id = ? AND sku_id = ?
            """;

        return jdbcTemplate.query(sql, RESERVATION_ROW_MAPPER, orderId, skuId)
            .stream()
            .findFirst();
    }

    // 5. Cập nhật trạng thái Reservation
    public int updateReservationStatus(UUID id, ReservationStatus status) {
        String sql = """
            UPDATE stock_reservations
            SET status = ?
            WHERE id = ?
            """;

        return jdbcTemplate.update(sql, status.name(), id);
    }

    // 6. Tìm danh sách các đơn giữ hàng đã quá hạn (tận dụng index composite)
    public List<StockReservation> findExpiredReservations(Instant now, int limit) {
        String sql = """
            SELECT id, order_id, sku_id, quantity, status, expires_at, created_at
            FROM stock_reservations
            WHERE status = 'RESERVED' AND expires_at < ?
            ORDER BY expires_at ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, RESERVATION_ROW_MAPPER, now, limit);
    }
}

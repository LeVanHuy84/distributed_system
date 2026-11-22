package com.flashorder.order.repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;

@Repository
public class OrderJdbcRepository {
	private final JdbcTemplate jdbcTemplate;

	public OrderJdbcRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	private static final RowMapper<Order> ORDER_ROW_MAPPER = (rs, rowNum) -> new Order(rs.getObject("id", UUID.class),
			rs.getString("user_id"), OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("total_amount"),
			rs.getTimestamp("created_at").toInstant());

	// 1. Thêm order vào bảng
	public void save(Order order) {
		String sql = """
				INSERT INTO orders (id, user_id, status, total_amount, created_at)
				VALUES (?, ?, ?, ?, ?)
				""";

		jdbcTemplate.update(sql, order.id(), order.userId(), order.status().name(), order.totalAmount(),
				Timestamp.from(order.createdAt()));
	}

	// 2. Tìm kiếm theo orderId
	public Optional<Order> findById(UUID id) {
		String sql = """
				SELECT id, user_id, status, total_amount, created_at
				FROM orders
				WHERE id = ?
				""";

		return jdbcTemplate.query(sql, ORDER_ROW_MAPPER, id).stream().findFirst();
	}

	public int updateStatus(UUID id, OrderStatus status) {
		String sql = """
				UPDATE orders
				SET status = ?
				WHERE id = ?
				""";

		return jdbcTemplate.update(sql, status.name(), id);
	}
}

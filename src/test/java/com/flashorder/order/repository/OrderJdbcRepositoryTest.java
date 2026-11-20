package com.flashorder.order.repository;

import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderJdbcRepository.class)
class OrderJdbcRepositoryTest {

    @Autowired
    private OrderJdbcRepository repository;

    @Test
    void shouldSaveAndFindOrderById() {
        // Arrange
        UUID orderId = UUID.randomUUID();
        String userId = "user-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("299.99");
        Instant now = Instant.now();

        Order order = new Order(
            orderId,
            userId,
            OrderStatus.PENDING,
            amount,
            now
        );

        // Act
        repository.save(order);
        Optional<Order> result = repository.findById(orderId);

        // Assert
        assertThat(result).isPresent();
        Order found = result.get();
        assertThat(found.id()).isEqualTo(orderId);
        assertThat(found.userId()).isEqualTo(userId);
        assertThat(found.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.totalAmount()).isEqualByComparingTo(amount);
    }

    @Test
    void shouldUpdateOrderStatus() {
        // Arrange
        UUID orderId = UUID.randomUUID();
        String userId = "user-" + UUID.randomUUID();
        Order order = new Order(
            orderId,
            userId,
            OrderStatus.PENDING,
            BigDecimal.valueOf(100.50),
            Instant.now()
        );
        repository.save(order);

        // Act
        int rowsUpdated = repository.updateStatus(orderId, OrderStatus.COMPLETED);
        Optional<Order> result = repository.findById(orderId);

        // Assert
        assertThat(rowsUpdated).isEqualTo(1);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void shouldReturnEmptyWhenOrderNotFound() {
        // Act
        Optional<Order> result = repository.findById(UUID.randomUUID());

        // Assert
        assertThat(result).isEmpty();
    }
}

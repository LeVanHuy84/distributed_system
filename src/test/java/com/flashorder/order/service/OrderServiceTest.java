package com.flashorder.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flashorder.inventory.domain.ReservationResult;
import com.flashorder.inventory.domain.ReservationStatus;
import com.flashorder.inventory.exception.InsufficientStockException;
import com.flashorder.inventory.service.InventoryReservationService;
import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.repository.OrderJdbcRepository;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;

/** Tests the success contract and rollback configuration of OrderService. */
@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

	@Mock
	private OrderJdbcRepository orderJdbcRepository;

	@Mock
	private InventoryReservationService inventoryReservationService;

	@InjectMocks
	private OrderService orderService;

	@Test
	@DisplayName("Tạo đơn hàng thành công khi kho còn đủ hàng")
	void shouldCreateOrderSuccessfulWhenStockIsAvailable() {
		// 1. Given
		CreateOrderCommand command = new CreateOrderCommand("user-1", "SKU-IPHONE15", 2, new BigDecimal("1000.00"),
				"ATOMIC_UPDATE");

		ReservationResult mockReservation = new ReservationResult(UUID.randomUUID(), UUID.randomUUID(), "SKU-IPHONE15",
				2, ReservationStatus.RESERVED, Instant.now().plusSeconds(600), Instant.now());

		when(inventoryReservationService.reserveStock(any(String.class), any(UUID.class), anyInt(), anyString()))
				.thenReturn(mockReservation);

		// 2. When Then
		CreateOrderResult result = orderService.createOrder(command);

		// 3. Then
		assertThat(result).isNotNull();
		assertThat(result.userId()).isEqualTo("user-1");
		assertThat(result.orderStatus()).isEqualTo(OrderStatus.PENDING);

		// Kiểm tra orderJdbcRepository.save() được gọi đúng userId và totalAmount
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderJdbcRepository).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();
		assertThat(savedOrder.userId()).isEqualTo("user-1");
		assertThat(savedOrder.totalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
	}

	@Test
	@DisplayName("Không tạo đơn hàng và ném lỗi khi kho không đủ hàng")
	void shouldThrowExceptionAndNotSaveOrderWhenStockIsInsufficient() {
		// 1. Given
		CreateOrderCommand command = new CreateOrderCommand("user-1", "SKU-IPHONE15", 10, new BigDecimal("1000.00"),
				"ATOMIC_UPDATE");
		when(inventoryReservationService.reserveStock(any(String.class), any(UUID.class), anyInt(), anyString()))
				.thenThrow(new InsufficientStockException("SKU-IPHONE15", 10, 2));

		// 2. When & Then
		assertThatThrownBy(() -> orderService.createOrder(command)).isInstanceOf(InsufficientStockException.class);

		// Đảm bảo TUYỆT ĐỐI không gọi orderJdbcRepository.save()
		verify(orderJdbcRepository, never()).save(any());
	}

	@Test
	@DisplayName("Tìm kiếm order theo id thành công và trả về OrderResult")
	void shouldReturnOrderResultWhenOrderExists() {
		// 1. Given
		UUID orderId = UUID.randomUUID();
		Order mockOrder = new Order(orderId, "user-1", OrderStatus.PENDING, new BigDecimal("2000.00"), Instant.now());
		when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.of(mockOrder));

		// 2. When
		Optional<OrderResult> result = orderService.getOrderById(orderId);

		// 3. Then
		assertThat(result).isPresent();
		assertThat(result.get().orderId()).isEqualTo(orderId);
		assertThat(result.get().userId()).isEqualTo("user-1");
		assertThat(result.get().status()).isEqualTo(OrderStatus.PENDING);
		assertThat(result.get().totalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
	}

	@Test
	@DisplayName("Trả về Optional.empty khi không tìm thấy order")
	void shouldReturnEmptyWhenOrderNotFound() {
		// 1. Given
		UUID orderId = UUID.randomUUID();
		when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.empty());

		// 2. When
		Optional<OrderResult> result = orderService.getOrderById(orderId);

		// 3. Then
		assertThat(result).isEmpty();
	}
}

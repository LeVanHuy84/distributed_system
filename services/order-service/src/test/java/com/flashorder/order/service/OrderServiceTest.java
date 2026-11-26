package com.flashorder.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashorder.order.domain.Order;
import com.flashorder.order.domain.OrderStatus;
import com.flashorder.order.repository.OrderJdbcRepository;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;
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

/** Tests the decoupled CRUD and payment state transitions of OrderService. */
@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @Mock
    private OrderJdbcRepository orderJdbcRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("Tạo đơn hàng thành công ở trạng thái PENDING")
    void shouldCreateOrderSuccessfully() {
        CreateOrderCommand command =
                new CreateOrderCommand("user-1", "SKU-IPHONE15", 2, new BigDecimal("1000.00"), "ATOMIC_UPDATE");

        CreateOrderResult result = orderService.createOrder(command);

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo("user-1");
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.totalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderJdbcRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.userId()).isEqualTo("user-1");
        assertThat(savedOrder.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(savedOrder.totalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("Tìm kiếm order theo id thành công và trả về OrderResult")
    void shouldReturnOrderResultWhenOrderExists() {
        UUID orderId = UUID.randomUUID();
        Order mockOrder = new Order(orderId, "user-1", OrderStatus.PENDING, new BigDecimal("2000.00"), Instant.now());
        when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.of(mockOrder));

        Optional<OrderResult> result = orderService.getOrderById(orderId);

        assertThat(result).isPresent();
        assertThat(result.get().orderId()).isEqualTo(orderId);
        assertThat(result.get().userId()).isEqualTo("user-1");
        assertThat(result.get().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.get().totalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("Trả về Optional.empty khi không tìm thấy order")
    void shouldReturnEmptyWhenOrderNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.empty());

        Optional<OrderResult> result = orderService.getOrderById(orderId);

        assertThat(result).isEmpty();
    }

    @Test
    public void payOrderCompletesPendingOrder() {
        UUID orderId = UUID.randomUUID();
        Order mockOrder = new Order(orderId, "USER-1", OrderStatus.PENDING, new BigDecimal("2000.00"), Instant.now());
        when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.of(mockOrder));

        OrderResult result = orderService.payOrder(orderId);

        assertThat(result.status()).isEqualTo(OrderStatus.COMPLETED);
        verify(orderJdbcRepository, times(1)).updateStatus(orderId, OrderStatus.COMPLETED);
    }

    @Test
    public void payOrderOrderNotFoundThrowsIllegalArgumentException() {
        UUID orderId = UUID.randomUUID();
        when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.payOrder(orderId)).isInstanceOf(IllegalArgumentException.class);
        verify(orderJdbcRepository, never()).updateStatus(any(), any());
    }

    @Test
    void payOrder_OrderAlreadyCompleted_ThrowsIllegalStateException() {
        UUID orderId = UUID.randomUUID();
        Order completedOrder =
                new Order(orderId, "USER-1", OrderStatus.COMPLETED, new BigDecimal("2000.00"), Instant.now());
        when(orderJdbcRepository.findById(orderId)).thenReturn(Optional.of(completedOrder));

        assertThatThrownBy(() -> orderService.payOrder(orderId)).isInstanceOf(IllegalStateException.class);
        verify(orderJdbcRepository, never()).updateStatus(any(), any());
    }
}

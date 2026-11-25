package com.flashorder.order.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.flashorder.mock.user.UserService;
import com.flashorder.order.api.dto.CreateOrderRequest;
import com.flashorder.order.api.dto.OrderResponse;
import com.flashorder.order.service.OrderService;
import com.flashorder.order.service.dto.CreateOrderCommand;
import com.flashorder.order.service.dto.CreateOrderResult;
import com.flashorder.order.service.dto.OrderResult;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderService orderService;
	private final UserService userService;

	public OrderController(OrderService orderService, UserService userService) {
		this.orderService = orderService;
		this.userService = userService;
	}

	@PostMapping
	public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request,
			@RequestHeader(value = "X-Concurrency-Strategy", required = false) String strategyHeader) {
		if (!userService.validateUser(request.userId())) {
			throw new IllegalArgumentException("Invalid user: " + request.userId());
		}

		CreateOrderCommand command = new CreateOrderCommand(request.userId(), request.skuId(), request.quantity(),
				request.unitPrice(), strategyHeader);

		CreateOrderResult result = orderService.createOrder(command);

		OrderResponse response = OrderResponse.from(result);

		// Build Location header
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.orderId())
				.toUri();

		return ResponseEntity.created(location).body(response);
	}

	@GetMapping("/{id}")
	public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id) {
		return orderService.getOrderById(id).map(OrderResponse::from).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/{id}/pay")
	public ResponseEntity<OrderResponse> payOrder(@PathVariable UUID id) {
		OrderResult result = orderService.payOrder(id);
		return ResponseEntity.ok(OrderResponse.from(result));
	}
}

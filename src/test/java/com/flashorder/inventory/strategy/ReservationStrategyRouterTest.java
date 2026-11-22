package com.flashorder.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flashorder.inventory.domain.ConcurrencyStrategy;

public class ReservationStrategyRouterTest {

	private StockReservationStrategy strategy1;
	private StockReservationStrategy strategy2;
	private StockReservationStrategy strategy3;

	private ReservationStrategyRouter router;

	@BeforeEach
	void setup() {
		// 1. Mock
		strategy1 = mock(StockReservationStrategy.class);
		strategy2 = mock(StockReservationStrategy.class);
		strategy3 = mock(StockReservationStrategy.class);

		// 2. Stub getStrategy() cho từng strategy
		when(strategy1.getStrategy()).thenReturn(ConcurrencyStrategy.OPTIMISTIC);

		when(strategy2.getStrategy()).thenReturn(ConcurrencyStrategy.PESSIMISTIC);

		when(strategy3.getStrategy()).thenReturn(ConcurrencyStrategy.ATOMIC_UPDATE);

		// 3. Router
		router = new ReservationStrategyRouter(List.of(strategy1, strategy2, strategy3));
	}

	@Test
	@DisplayName("Should resolve strategy case-insensitively")
	void shouldResolveStrategyCaseInsensitively() {
		StockReservationStrategy actual = router.resolve("optimistic");

		assertThat(actual).isSameAs(strategy1);
	}

	@Test
	@DisplayName("Should fallback to default when header is blank")
	void shouldFallbackToDefaultWhenHeaderIsBlank() {
		StockReservationStrategy resultForNull = router.resolve(null);
		StockReservationStrategy resultForBlank = router.resolve("");

		assertThat(resultForNull).isSameAs(strategy3);
		assertThat(resultForBlank).isSameAs(strategy3);
	}

	@Test
	@DisplayName("Should throw exception for unsupported strategy")
	void shouldThrowExceptionForUnsupportedStrategy() {
		assertThatThrownBy(() -> router.resolve("MAGIC_LOCK")).isInstanceOf(IllegalArgumentException.class);
	}
}

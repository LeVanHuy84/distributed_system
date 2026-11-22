package com.flashorder.inventory.strategy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.flashorder.inventory.domain.ConcurrencyStrategy;

@Component
public class ReservationStrategyRouter {

	private static final ConcurrencyStrategy DEFAULT_STRATEGY = ConcurrencyStrategy.ATOMIC_UPDATE;

	private final Map<ConcurrencyStrategy, StockReservationStrategy> strategyMap;

	public ReservationStrategyRouter(List<StockReservationStrategy> strategies) {
		this.strategyMap = strategies.stream()
				.collect(Collectors.toMap(
						(StockReservationStrategy reservationStrategy) -> reservationStrategy.getStrategy(),
						Function.identity()));
	}

	public StockReservationStrategy getStrategy(ConcurrencyStrategy strategy) {
		if (strategy == null) {
			strategy = DEFAULT_STRATEGY;
		}

		StockReservationStrategy strategyImpl = strategyMap.get(strategy);

		if (strategyImpl == null) {
			throw new IllegalArgumentException("Strategy not registered: " + strategy);
		}

		return strategyImpl;
	}

	public StockReservationStrategy resolve(String strategyHeader) {
		if (strategyHeader == null || strategyHeader.isBlank()) {
			return getStrategy(DEFAULT_STRATEGY);
		}

		try {
			ConcurrencyStrategy strategy = ConcurrencyStrategy.valueOf(strategyHeader.trim().toUpperCase());

			return getStrategy(strategy);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported concurrency strategy: " + strategyHeader + ". Supported: "
					+ Arrays.toString(ConcurrencyStrategy.values()), ex);
		}
	}
}

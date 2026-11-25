package com.flashorder.support.concurrency.dto;

import java.util.List;

/**
 * ConcurrentExcutionResult<T>
 */
public record ConcurrentExecutionResult<T>(List<T> successes, List<Throwable> failures, long durationMs) {
	public int successCount() {
		return successes.size();
	}

	public int failureCount() {
		return failures.size();
	}

	public int totalCount() {
		return successCount() + failureCount();
	}

	/**
	 * Loc danh sach loi theo kieu exception cu the
	 */
	@SuppressWarnings("unchecked")
	public <E extends Throwable> List<E> failuresOfType(Class<E> errorType) {
		return failures.stream().filter(errorType::isInstance).map(e -> (E) e).toList();
	}
}

package com.flashorder.common.exception;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(String error, String message, int status, Instant timestamp,
		Map<String, String> details) {

}

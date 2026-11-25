package com.flashorder.common.exception;

import com.flashorder.inventory.exception.ConcurrencyConflictException;
import com.flashorder.inventory.exception.InsufficientStockException;
import com.flashorder.inventory.exception.SkuNotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientStock(InsufficientStockException ex) {

        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrencyConflict(ConcurrencyConflictException ex) {

        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(SkuNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSkuNotFound(SkuNotFoundException ex) {

        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {

        Map<String, String> details = new LinkedHashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));

        return buildResponse(HttpStatus.BAD_REQUEST, "Request validation failed", details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {

        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex) {

        log.error("Unexpected server error", ex);

        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred", null);
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status, String message, Map<String, String> details) {

        ApiErrorResponse response =
                new ApiErrorResponse(status.getReasonPhrase(), message, status.value(), Instant.now(), details);

        return ResponseEntity.status(status).body(response);
    }
}

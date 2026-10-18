package com.evcharging.security;

import java.time.Instant;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.evcharging.global.exception.handler.ApiError;

import jakarta.servlet.http.HttpServletRequest;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SessionLimitExceptionHandler {
    @ExceptionHandler(SessionLimitException.class)
    public ResponseEntity<ApiError> handle(SessionLimitException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(error(exception, request));
    }

    static ApiError error(SessionLimitException exception, HttpServletRequest request) {
        return new ApiError(Instant.now(), exception.getStatus(), exception.getCode(),
                exception.getMessage(), request.getRequestURI(), Map.of());
    }
}

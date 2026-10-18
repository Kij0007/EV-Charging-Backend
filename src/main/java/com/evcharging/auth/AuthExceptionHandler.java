package com.evcharging.auth;

import java.time.Instant;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.evcharging.global.exception.handler.ApiError;

import jakarta.servlet.http.HttpServletRequest;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> authentication(AuthenticationException exception, HttpServletRequest request) {
        return ResponseEntity.status(401).body(new ApiError(Instant.now(), 401, "INVALID_CREDENTIALS",
                "Email or password is incorrect", request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiError> throttled(TooManyAttemptsException exception, HttpServletRequest request) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(new ApiError(Instant.now(), 429,
                "TOO_MANY_ATTEMPTS", exception.getMessage(), request.getRequestURI(), Map.of()));
    }
}

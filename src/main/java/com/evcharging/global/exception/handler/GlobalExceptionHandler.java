package com.evcharging.global.exception.handler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.BookingNotFoundException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.PaymentFailedException;
import com.evcharging.exception.StationNotFoundException;
import com.evcharging.exception.UserNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleForbidden(Exception exception, WebRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action", request, Map.of());
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(Exception exception, WebRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sign in to continue", request, Map.of());
    }

    @ExceptionHandler({UserNotFoundException.class, BookingNotFoundException.class,
            StationNotFoundException.class})
    public ResponseEntity<Object> handleNotFound(RuntimeException exception, WebRequest request) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(DomainConflictException.class)
    public ResponseEntity<Object> handleConflict(DomainConflictException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "DOMAIN_CONFLICT", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Object> handleBadRequest(BadRequestException exception, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<Object> handlePaymentFailure(PaymentFailedException exception, WebRequest request) {
        return error(HttpStatus.BAD_GATEWAY, "PAYMENT_FAILED", "Mock payment processing failed", request, Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataConflict(DataIntegrityViolationException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "Request conflicts with existing data or a database constraint", request, Map.of());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Object> handleLockConflict(PessimisticLockingFailureException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "Resource is being modified; retry the request", request, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                fields.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, fields);
    }

    // Spring's superclass routes MVC errors here while preserving statuses and headers (e.g. Allow).
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        String code = "HTTP_" + status.value();
        String message = status.is5xxServerError() ? "An unexpected server error occurred"
                : reasonPhrase(status);
        if (exception instanceof MethodArgumentNotValidException validation) {
            code = "VALIDATION_FAILED";
            message = "Request validation failed";
            for (FieldError field : validation.getBindingResult().getFieldErrors()) {
                fields.putIfAbsent(field.getField(), field.getDefaultMessage() == null
                        ? "Invalid value" : field.getDefaultMessage());
            }
            validation.getBindingResult().getGlobalErrors().forEach(validationError ->
                    fields.putIfAbsent(validationError.getObjectName(), validationError.getDefaultMessage() == null
                            ? "Invalid value" : validationError.getDefaultMessage()));
        } else if (exception instanceof HttpMessageNotReadableException) {
            code = "MALFORMED_JSON";
            message = "Request body is missing or contains invalid JSON";
        } else if (exception instanceof MissingServletRequestParameterException missingParameter) {
            code = "MISSING_PARAMETER";
            message = "Required request parameter is missing";
            fields.put(missingParameter.getParameterName(), "Required");
        } else if (exception instanceof MethodArgumentTypeMismatchException typeMismatch) {
            code = "INVALID_PARAMETER";
            message = "Request parameter has an invalid type";
            fields.put(typeMismatch.getName(), "Invalid type");
        }
        return new ResponseEntity<>(new ApiError(Instant.now(), status.value(), code, message,
                path(request), fields), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        // Do not log untrusted exception messages, request bodies, or provider details.
        log.error("Unhandled request failure of type {}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected server error occurred", request, Map.of());
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message,
            WebRequest request, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code,
                message, path(request), fields));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI() : "";
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        return knownStatus == null ? "Request could not be processed" : knownStatus.getReasonPhrase();
    }
}

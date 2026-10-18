package com.evcharging.global.exception.handler;

import java.time.Instant;
import java.util.Map;

public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, Map<String, String> fieldErrors) {
    public ApiError {
        fieldErrors = Map.copyOf(fieldErrors);
    }
}

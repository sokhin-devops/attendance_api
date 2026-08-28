package com.attendance.api.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Standard error body returned for every non-2xx response")
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @Schema(description = "Field-level validation failures, when applicable")
        Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ApiErrorResponse validation(String message, String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), 400, "Bad Request", message, path, fieldErrors);
    }
}

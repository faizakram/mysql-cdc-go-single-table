package com.migration.platform.common;

import java.time.OffsetDateTime;
import java.util.List;

/** Consistent error envelope returned by the API (seeds OpenAPI error contract, issue #24). */
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        List<String> details
) {
    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(OffsetDateTime.now(), status, error, message, details);
    }
}

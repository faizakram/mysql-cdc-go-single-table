package com.migration.platform.alert.dto;

import com.migration.platform.alert.Alert;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AlertResponse(
        UUID id, UUID projectId, String severity, String type, String message,
        String status, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
    public static AlertResponse from(Alert a) {
        return new AlertResponse(a.getId(), a.getProjectId(), a.getSeverity(), a.getType(),
                a.getMessage(), a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
    }
}

package com.migration.platform.project.dto;

import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        ProjectStatus status,
        UUID sourceConnectionId,
        UUID targetConnectionId,
        Map<String, Object> config,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ProjectResponse from(MigrationProject p) {
        return new ProjectResponse(
                p.getId(), p.getName(), p.getDescription(), p.getStatus(),
                p.getSourceConnectionId(), p.getTargetConnectionId(), p.getConfig(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}

package com.migration.platform.job.dto;

import com.migration.platform.job.JobStatus;
import com.migration.platform.job.MigrationJob;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobResponse(
        UUID id,
        UUID projectId,
        JobStatus status,
        String phase,
        String sourceConnectorName,
        String sinkConnectorName,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static JobResponse from(MigrationJob j) {
        return new JobResponse(
                j.getId(), j.getProjectId(), j.getStatus(), j.getPhase(),
                j.getSourceConnectorName(), j.getSinkConnectorName(), j.getError(),
                j.getStartedAt(), j.getFinishedAt(), j.getCreatedAt(), j.getUpdatedAt());
    }
}

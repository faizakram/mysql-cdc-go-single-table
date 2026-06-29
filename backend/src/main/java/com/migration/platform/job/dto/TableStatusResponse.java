package com.migration.platform.job.dto;

import com.migration.platform.job.TableStatus;

import java.time.OffsetDateTime;

public record TableStatusResponse(
        String schemaName, String tableName, String phase, String status,
        long rowsSynced, Long totalRows, String error, OffsetDateTime updatedAt,
        Long lastLagMs, OffsetDateTime startedAt
) {
    public static TableStatusResponse from(TableStatus t) {
        return new TableStatusResponse(t.getSchemaName(), t.getTableName(), t.getPhase(),
                t.getStatus(), t.getRowsSynced(), t.getTotalRows(), t.getError(), t.getUpdatedAt(),
                t.getLastLagMs(), t.getStartedAt());
    }
}

package com.migration.platform.validation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Advanced validation read models (#96). */
public final class ValidationDtos {

    private ValidationDtos() {}

    public record TableValidation(
            String schema, String table,
            long sourceRows, long targetRows,
            long nullPrimaryKey, long duplicateKeys,
            long missingRows, long extraRows,
            long cdcInserts, long cdcUpdates, long cdcDeletes,  // CDC change activity on the source; -1 if N/A
            String status,            // PASS | FAIL | ERROR
            List<String> issues
    ) {}

    public record ValidationReport(
            int tables, int passed, int syncing, int failed,
            List<TableValidation> results
    ) {}

    /**
     * A background validation run (#150) with the per-table results computed so far. Drives the
     * UI's live progress: {@code completedTables}/{@code totalTables} and the running pass/fail
     * tally update as the run streams in.
     */
    public record ValidationRunDto(
            UUID id, UUID projectId, String status,
            int totalTables, int completedTables, int passed, int syncing, int failed,
            String error,
            OffsetDateTime startedAt, OffsetDateTime finishedAt,
            List<TableValidation> results
    ) {}
}

package com.migration.platform.reconciliation.dto;

import com.migration.platform.reconciliation.ReconciliationResult;
import com.migration.platform.reconciliation.ReconciliationRun;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ReconciliationDtos {

    public record ResultDto(
            String schemaName, String tableName,
            Long sourceCount, Long targetCount, Long difference,
            Long sampled, Long missing, Long changed,
            String status, String error
    ) {
        public static ResultDto from(ReconciliationResult r) {
            return new ResultDto(r.getSchemaName(), r.getTableName(), r.getSourceCount(),
                    r.getTargetCount(), r.getDifference(), r.getSampled(), r.getMissing(), r.getChanged(),
                    r.getStatus(), r.getError());
        }
    }

    public record RunDto(
            UUID id, UUID projectId, String status, String mode, int totalTables, int mismatched,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, List<ResultDto> results
    ) {
        public static RunDto from(ReconciliationRun run, List<ReconciliationResult> results) {
            return new RunDto(run.getId(), run.getProjectId(), run.getStatus(), run.getMode(),
                    run.getTotalTables(), run.getMismatched(), run.getStartedAt(), run.getFinishedAt(),
                    results.stream().map(ResultDto::from).toList());
        }
    }

    private ReconciliationDtos() {}
}

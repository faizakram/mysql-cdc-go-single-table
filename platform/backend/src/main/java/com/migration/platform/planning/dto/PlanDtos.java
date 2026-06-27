package com.migration.platform.planning.dto;

import java.util.List;

/** Migration-plan read models (#88). */
public final class PlanDtos {

    private PlanDtos() {}

    /** Input row for the pure planner. */
    public record TableInput(String fqName, long rowCount, long bytes, boolean hasPk, int unmappableColumns) {}

    /** A table in the plan with its migration order level (level -1 = part of a cycle). */
    public record PlanTable(String fqName, long rowCount, long bytes, int level, boolean hasPk, List<String> risks) {}

    public record MigrationPlan(
            List<PlanTable> tables,   // ordered: parents before children
            int levels,               // number of parallel waves
            boolean hasCycles,
            long totalRows,
            long totalBytes,
            long estimatedSeconds,
            List<String> risks
    ) {}
}

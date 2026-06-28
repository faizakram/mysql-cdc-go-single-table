package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure assessment logic for advanced validation (#96): turns raw per-table metrics
 * (counts, null-PK, duplicate keys, missing/extra) into a status + issue list. No DB access.
 *
 * <p>Status is classified by the <em>nature</em> of the discrepancy, not mere count equality (#166),
 * so live-write replication lag isn't reported as data corruption:
 * <ul>
 *   <li><b>FAIL</b> — a real correctness problem: rows extra on the target ({@code extra > 0} —
 *       over-replication / a lost delete), duplicate keys, null primary keys, or sampled source
 *       rows genuinely missing on the target ({@code missing > 0}).</li>
 *   <li><b>SYNCING</b> — not a failure: the only discrepancy is the target trailing the source by
 *       row count ({@code source > target}) with every structural signal clean — rows still in
 *       flight (CDC replication lag). Resolves to PASS once the target catches up.</li>
 *   <li><b>PASS</b> — counts equal and all signals clean.</li>
 * </ul>
 */
public final class ValidationLogic {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SYNCING = "SYNCING";

    private ValidationLogic() {}

    public static TableValidation assess(String schema, String table,
                                         long sourceRows, long targetRows,
                                         long nullPk, long duplicateKeys,
                                         long missing, long extra,
                                         long cdcInserts, long cdcUpdates, long cdcDeletes) {
        List<String> issues = new ArrayList<>();
        if (nullPk > 0) issues.add("NULL_PRIMARY_KEY(" + nullPk + ")");
        if (duplicateKeys > 0) issues.add("DUPLICATE_KEYS(" + duplicateKeys + ")");
        if (missing > 0) issues.add("MISSING_ROWS(" + missing + ")");
        if (extra > 0) issues.add("EXTRA_ROWS(" + extra + ")");

        // A real correctness problem: target has rows it shouldn't (extra), dupes, null PKs, or
        // sampled source rows are absent on the target (missing).
        boolean structural = nullPk > 0 || duplicateKeys > 0 || missing > 0 || extra > 0;

        String status;
        if (structural) {
            if (sourceRows != targetRows) {
                issues.add("ROW_COUNT_MISMATCH(" + sourceRows + " vs " + targetRows + ")");
            }
            status = FAIL;
        } else if (sourceRows == targetRows) {
            status = PASS;
        } else {
            // No structural anomaly and the target is simply behind on count → rows in flight.
            // (target > source would have produced extra > 0 above and been a FAIL.)
            issues.add("TARGET_BEHIND(" + sourceRows + " vs " + targetRows + ", "
                    + (sourceRows - targetRows) + " in flight)");
            status = SYNCING;
        }
        // CDC op counts are informational visibility only — they don't affect status.
        return new TableValidation(schema, table, sourceRows, targetRows, nullPk, duplicateKeys,
                missing, extra, cdcInserts, cdcUpdates, cdcDeletes, status, issues);
    }
}

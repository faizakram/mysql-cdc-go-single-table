package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure assessment logic for advanced validation (#96): turns raw per-table metrics
 * (counts, null-PK, duplicate keys, missing/extra) into a status + issue list. No DB access.
 */
public final class ValidationLogic {

    private ValidationLogic() {}

    public static TableValidation assess(String schema, String table,
                                         long sourceRows, long targetRows,
                                         long nullPk, long duplicateKeys,
                                         long missing, long extra) {
        List<String> issues = new ArrayList<>();
        if (sourceRows != targetRows) issues.add("ROW_COUNT_MISMATCH(" + sourceRows + " vs " + targetRows + ")");
        if (nullPk > 0) issues.add("NULL_PRIMARY_KEY(" + nullPk + ")");
        if (duplicateKeys > 0) issues.add("DUPLICATE_KEYS(" + duplicateKeys + ")");
        if (missing > 0) issues.add("MISSING_ROWS(" + missing + ")");
        if (extra > 0) issues.add("EXTRA_ROWS(" + extra + ")");
        String status = issues.isEmpty() ? "PASS" : "FAIL";
        return new TableValidation(schema, table, sourceRows, targetRows, nullPk, duplicateKeys,
                missing, extra, status, issues);
    }
}

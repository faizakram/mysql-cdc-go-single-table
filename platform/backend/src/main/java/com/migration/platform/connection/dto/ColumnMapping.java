package com.migration.platform.connection.dto;

/**
 * Proposed source→target mapping for one column (issue #37 editor / #31 mapping engine).
 * {@code semantic} is NONE | UUID | JSON — drives the TypeConversionTransform designation.
 */
public record ColumnMapping(
        String column,
        String sourceType,
        int size,
        boolean nullable,
        boolean primaryKey,
        String proposedType,
        String semantic
) {}

package com.migration.platform.connection.dto;

/**
 * Proposed source→target mapping for one column (issue #37 editor / #31 mapping engine).
 * {@code semantic} is NONE | UUID | JSON — drives the TypeConversionTransform designation.
 * {@code note} flags lossy or unsupported mappings (#29), e.g. spatial/hierarchyid → TEXT.
 * {@code scale} carries DECIMAL/NUMERIC digits-after-point (#197); the proposed type already
 * encodes (precision, scale) in its rendered form.
 */
public record ColumnMapping(
        String column,
        String sourceType,
        int size,
        int scale,
        boolean nullable,
        boolean primaryKey,
        String proposedType,
        String semantic,
        String note
) {
    /** Back-compat: mappings without a meaningful scale (non-numeric types, tests). */
    public ColumnMapping(String column, String sourceType, int size, boolean nullable, boolean primaryKey,
                         String proposedType, String semantic, String note) {
        this(column, sourceType, size, 0, nullable, primaryKey, proposedType, semantic, note);
    }
}

package com.migration.platform.connection.dto;

public record ColumnInfo(
        String name,
        String dataType,
        int size,
        int scale,        // digits after the decimal point for DECIMAL/NUMERIC (#197); 0 otherwise
        boolean nullable,
        boolean primaryKey
) {
    /** Back-compat: columns without a meaningful scale (non-numeric types, Mongo, tests). */
    public ColumnInfo(String name, String dataType, int size, boolean nullable, boolean primaryKey) {
        this(name, dataType, size, 0, nullable, primaryKey);
    }
}

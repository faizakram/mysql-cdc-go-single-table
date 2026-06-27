package com.migration.platform.connection.dto;

public record ColumnInfo(
        String name,
        String dataType,
        int size,
        boolean nullable,
        boolean primaryKey
) {}

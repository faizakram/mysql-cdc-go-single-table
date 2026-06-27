package com.migration.platform.connection.dto;

public record TableInfo(
        String schemaName,
        String tableName,
        boolean hasPrimaryKey,
        boolean cdcEnabled
) {}

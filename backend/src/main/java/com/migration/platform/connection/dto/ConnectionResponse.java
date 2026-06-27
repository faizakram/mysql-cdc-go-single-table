package com.migration.platform.connection.dto;

import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Safe projection — no password material. */
public record ConnectionResponse(
        UUID id,
        String name,
        DbType dbType,
        String host,
        Integer port,
        String databaseName,
        String username,
        Map<String, Object> options,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ConnectionResponse from(DbConnection c) {
        return new ConnectionResponse(
                c.getId(), c.getName(), c.getDbType(), c.getHost(), c.getPort(),
                c.getDatabaseName(), c.getUsername(), c.getOptions(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}

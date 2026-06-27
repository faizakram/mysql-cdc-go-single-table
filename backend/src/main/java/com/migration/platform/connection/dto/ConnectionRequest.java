package com.migration.platform.connection.dto;

import com.migration.platform.connection.DbType;
import jakarta.validation.constraints.*;

import java.util.Map;

/** Create/update payload. Password is write-only (accepted in, never returned). */
public record ConnectionRequest(
        @NotBlank String name,
        @NotNull DbType dbType,
        @NotBlank String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank String databaseName,
        @NotBlank String username,
        @NotBlank String password,
        Map<String, Object> options
) {}

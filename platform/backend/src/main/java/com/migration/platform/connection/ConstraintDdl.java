package com.migration.platform.connection;

import com.migration.platform.common.Names;
import com.migration.platform.connection.dto.ConstraintDtos.ForeignKeyInfo;
import com.migration.platform.connection.dto.ConstraintDtos.IndexInfo;

import java.util.stream.Collectors;

/**
 * Generates PostgreSQL DDL to replicate source indexes and foreign keys onto the target (issue #33),
 * with names/columns snake_cased to match the migrated schema. Pure + unit-tested; the JDBC sink
 * auto-creates tables + PKs, so this adds the secondary indexes and FKs after the initial load.
 */
public final class ConstraintDdl {

    public static String indexDdl(String targetSchema, String sourceTable, IndexInfo idx) {
        String table = Names.snakeCase(sourceTable);
        String cols = idx.columns().stream().map(Names::snakeCase).collect(Collectors.joining(", "));
        String name = Names.snakeCase(idx.name());
        return "CREATE " + (idx.unique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS " + name
                + " ON " + targetSchema + "." + table + " (" + cols + ");";
    }

    public static String foreignKeyDdl(String targetSchema, String sourceTable, ForeignKeyInfo fk) {
        String table = Names.snakeCase(sourceTable);
        String cols = fk.columns().stream().map(Names::snakeCase).collect(Collectors.joining(", "));
        String refCols = fk.refColumns().stream().map(Names::snakeCase).collect(Collectors.joining(", "));
        String name = Names.snakeCase(fk.name());
        return "ALTER TABLE " + targetSchema + "." + table + " ADD CONSTRAINT " + name
                + " FOREIGN KEY (" + cols + ") REFERENCES "
                + targetSchema + "." + Names.snakeCase(fk.refTable()) + " (" + refCols + ");";
    }

    private ConstraintDdl() {}
}

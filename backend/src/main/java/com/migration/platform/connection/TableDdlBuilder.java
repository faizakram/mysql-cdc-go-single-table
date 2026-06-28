package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnMapping;
import com.migration.platform.connector.NamingStrategy;
import com.migration.platform.connector.TargetNaming;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates cross-dialect {@code CREATE TABLE} DDL for the CDC-free bulk-copy full load (#191).
 *
 * <p>The Debezium JDBC sink auto-creates target tables for the CDC path; the bulk-copy path doesn't
 * use Debezium, so it must create the target tables itself. Column types come from the same
 * {@link com.migration.platform.connection.TypeMappingService} proposals the UI shows, so a bulk
 * load lands the same target shape a CDC migration would. Identifiers are quoted per dialect (reusing
 * {@link TargetSchemaService}'s helpers) and renamed per the project's {@link NamingStrategy}.
 */
@Component
public class TableDdlBuilder {

    /** Schema-qualified, dialect-quoted table name (no schema segment on MySQL). */
    public static String qualified(DbType t, String schema, String table) {
        return (t != DbType.MYSQL ? TargetSchemaService.quote(t, schema) + "." : "")
                + TargetSchemaService.quoteId(t, table);
    }

    public static String quoteIdent(DbType t, String id) {
        return TargetSchemaService.quoteId(t, id);
    }

    /** {@code DROP TABLE IF EXISTS} per dialect; Oracle/Db2 lack the clause, so the caller ignores errors. */
    public String dropIfExists(DbType t, String schema, String table) {
        String q = qualified(t, schema, table);
        return switch (t) {
            case POSTGRESQL, MYSQL, SQLSERVER -> "DROP TABLE IF EXISTS " + q;
            default -> "DROP TABLE " + q; // ORACLE/DB2 — caller tolerates "does not exist"
        };
    }

    /**
     * Build a CREATE TABLE for {@code target}, mapping each source column via the supplied
     * {@link ColumnMapping}s (proposed target type, nullability, PK) and applying {@code naming} to
     * the table and column identifiers.
     */
    public String createTable(DbType target, String schema, String table,
                              List<ColumnMapping> columns, NamingStrategy naming) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ")
                .append(qualified(target, schema, table)).append(" (\n");
        String cols = columns.stream()
                .map(c -> "  " + quoteIdent(target, TargetNaming.apply(c.column(), naming))
                        + " " + c.proposedType()
                        + (c.nullable() ? "" : " NOT NULL"))
                .collect(Collectors.joining(",\n"));
        sb.append(cols);
        List<String> pk = columns.stream().filter(ColumnMapping::primaryKey)
                .map(c -> quoteIdent(target, TargetNaming.apply(c.column(), naming)))
                .toList();
        if (!pk.isEmpty()) {
            sb.append(",\n  PRIMARY KEY (").append(String.join(", ", pk)).append(")");
        }
        sb.append("\n)");
        return sb.toString();
    }
}

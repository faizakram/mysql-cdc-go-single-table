package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ColumnMapping;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proposes PostgreSQL target types for SQL Server source columns and auto-detects UUID/JSON
 * semantics. Ports the mapping logic from the legacy replicate-schema.py / TypeConversionTransform
 * into a single inspectable engine (issues #31, #37). Users override the proposals in the UI.
 */
@Service
public class TypeMappingService {

    private static final Pattern JSON_NAME =
            Pattern.compile(".*(preferences|settings|metadata|response|_json)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_VARCHAR = 10_485_760; // beyond this, prefer TEXT

    private final SchemaDiscoveryService discovery;

    public TypeMappingService(SchemaDiscoveryService discovery) {
        this.discovery = discovery;
    }

    public List<ColumnMapping> proposeForTable(UUID connectionId, String schema, String table) {
        return discovery.listColumns(connectionId, schema, table).stream()
                .map(this::propose)
                .toList();
    }

    /** Pure mapping for a single column — exposed for unit testing. */
    public ColumnMapping propose(ColumnInfo c) {
        String src = c.dataType() == null ? "" : c.dataType().toLowerCase();
        String pg = mapType(src, c.size());
        String semantic = detectSemantic(src, c.name());
        return new ColumnMapping(c.name(), c.dataType(), c.size(), c.nullable(), c.primaryKey(), pg, semantic);
    }

    private String mapType(String src, int size) {
        return switch (src) {
            case "tinyint", "smallint" -> "SMALLINT";
            case "int" -> "INTEGER";
            case "bigint" -> "BIGINT";
            case "decimal", "numeric" -> "NUMERIC";
            case "money" -> "NUMERIC(19,4)";
            case "smallmoney" -> "NUMERIC(10,4)";
            case "float" -> "DOUBLE PRECISION";
            case "real" -> "REAL";
            case "bit" -> "BOOLEAN";
            case "char", "nchar" -> sizedOrText("CHAR", size);
            case "varchar", "nvarchar" -> sizedOrText("VARCHAR", size);
            case "text", "ntext" -> "TEXT";
            case "date" -> "DATE";
            case "time" -> "TIME(6)";
            case "datetime", "datetime2", "smalldatetime" -> "TIMESTAMP(6)";
            case "datetimeoffset" -> "TIMESTAMPTZ(6)";
            case "binary", "varbinary", "image" -> "BYTEA";
            case "uniqueidentifier" -> "UUID";
            case "xml", "sql_variant", "geography", "geometry", "hierarchyid" -> "TEXT";
            default -> "TEXT";
        };
    }

    private String sizedOrText(String base, int size) {
        if (size <= 0 || size > MAX_VARCHAR) return "TEXT";
        return base + "(" + size + ")";
    }

    /** Conservative auto-detection; users can change it in the editor. */
    private String detectSemantic(String src, String name) {
        if ("uniqueidentifier".equals(src)) return "UUID";
        boolean stringLike = src.contains("char") || src.equals("text") || src.equals("ntext") || src.equals("xml");
        if (stringLike && !src.equals("xml") && JSON_NAME.matcher(name).matches()) return "JSON";
        return "NONE";
    }
}

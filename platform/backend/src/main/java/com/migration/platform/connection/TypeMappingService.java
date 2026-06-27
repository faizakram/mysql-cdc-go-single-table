package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ColumnMapping;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proposes PostgreSQL target types for SQL Server source columns and auto-detects UUID/JSON
 * semantics — a single inspectable mapping engine (issues #31, #37). The default rules can be
 * overridden per project via {@code config.typeMappingOverrides} ({"sourceType":"PG_TYPE"}), so
 * mappings are configurable without code edits.
 */
@Service
public class TypeMappingService {

    private static final Pattern JSON_NAME =
            Pattern.compile(".*(preferences|settings|metadata|response|_json)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_VARCHAR = 10_485_760;

    /** Lossy / not-natively-supported source types — flagged so users don't get silent surprises (#29). */
    private static final Map<String, String> TYPE_NOTES = Map.of(
            "geography", "Spatial type mapped to TEXT (lossy) — use PostGIS geography for fidelity",
            "geometry", "Spatial type mapped to TEXT (lossy) — use PostGIS geometry for fidelity",
            "hierarchyid", "hierarchyid mapped to TEXT (lossy; no native PostgreSQL equivalent)",
            "sql_variant", "sql_variant mapped to TEXT (lossy; mixed underlying types)",
            "xml", "XML mapped to TEXT (consider XML/JSONB if the content is JSON)",
            "image", "Deprecated image type mapped to BYTEA");

    /** Default non-parameterized MSSQL→PostgreSQL type rules (data, not code branches). */
    private static final Map<String, String> DEFAULT_TYPES = Map.ofEntries(
            Map.entry("tinyint", "SMALLINT"), Map.entry("smallint", "SMALLINT"),
            Map.entry("int", "INTEGER"), Map.entry("bigint", "BIGINT"),
            Map.entry("money", "NUMERIC(19,4)"), Map.entry("smallmoney", "NUMERIC(10,4)"),
            Map.entry("float", "DOUBLE PRECISION"), Map.entry("real", "REAL"),
            Map.entry("bit", "BOOLEAN"),
            Map.entry("text", "TEXT"), Map.entry("ntext", "TEXT"),
            Map.entry("date", "DATE"), Map.entry("time", "TIME(6)"),
            Map.entry("datetime", "TIMESTAMP(6)"), Map.entry("datetime2", "TIMESTAMP(6)"),
            Map.entry("smalldatetime", "TIMESTAMP(6)"), Map.entry("datetimeoffset", "TIMESTAMPTZ(6)"),
            Map.entry("binary", "BYTEA"), Map.entry("varbinary", "BYTEA"), Map.entry("image", "BYTEA"),
            Map.entry("uniqueidentifier", "UUID"),
            Map.entry("xml", "TEXT"), Map.entry("sql_variant", "TEXT"),
            Map.entry("geography", "TEXT"), Map.entry("geometry", "TEXT"), Map.entry("hierarchyid", "TEXT"));

    private final SchemaDiscoveryService discovery;
    private final ProjectRepository projects;
    private final ConnectionRepository connections;

    public TypeMappingService(SchemaDiscoveryService discovery, ProjectRepository projects,
                              ConnectionRepository connections) {
        this.discovery = discovery;
        this.projects = projects;
        this.connections = connections;
    }

    /** Per-table proposals for the project's source→target engine pair, with per-project overrides. */
    public List<ColumnMapping> proposeForTable(UUID connectionId, String schema, String table, UUID projectId) {
        Map<String, String> overrides = resolveOverrides(projectId);
        DbType srcEngine = connections.findById(connectionId).map(DbConnection::getDbType).orElse(DbType.SQLSERVER);
        DbType tgtEngine = resolveTargetEngine(projectId);
        return discovery.listColumns(connectionId, schema, table).stream()
                .map(c -> propose(c, overrides, srcEngine, tgtEngine))
                .toList();
    }

    public ColumnMapping propose(ColumnInfo c) {
        return propose(c, Map.of());
    }

    /** Backward-compatible default pair (SQL Server → PostgreSQL). */
    public ColumnMapping propose(ColumnInfo c, Map<String, String> overrides) {
        return propose(c, overrides, DbType.SQLSERVER, DbType.POSTGRESQL);
    }

    /** Pair-aware mapping for a single column (#81). The well-established SQL Server→PostgreSQL rules
     *  stay as-is; other pairs (and homogeneous) go through {@link TypeMappingMatrix}. */
    public ColumnMapping propose(ColumnInfo c, Map<String, String> overrides, DbType srcEngine, DbType tgtEngine) {
        String src = c.dataType() == null ? "" : c.dataType().toLowerCase();
        if (overrides != null && overrides.containsKey(src)) {
            return new ColumnMapping(c.name(), c.dataType(), c.size(), c.nullable(), c.primaryKey(),
                    overrides.get(src), detectSemantic(src, c.name()), null);
        }
        if (srcEngine == DbType.SQLSERVER && tgtEngine == DbType.POSTGRESQL) {
            String pg = mapType(src, c.size(), overrides);
            return new ColumnMapping(c.name(), c.dataType(), c.size(), c.nullable(), c.primaryKey(),
                    pg, detectSemantic(src, c.name()), TYPE_NOTES.get(src));
        }
        TypeMappingMatrix.Mapped m = TypeMappingMatrix.map(srcEngine, tgtEngine, c.dataType(), c.size());
        return new ColumnMapping(c.name(), c.dataType(), c.size(), c.nullable(), c.primaryKey(),
                m.targetType(), detectSemantic(src, c.name()), m.note());
    }

    private DbType resolveTargetEngine(UUID projectId) {
        if (projectId == null) return DbType.POSTGRESQL;
        return projects.findById(projectId)
                .map(MigrationProject::getTargetConnectionId)
                .flatMap(connections::findById)
                .map(DbConnection::getDbType)
                .orElse(DbType.POSTGRESQL);
    }

    private String mapType(String src, int size, Map<String, String> overrides) {
        if (overrides != null && overrides.containsKey(src)) {
            return overrides.get(src);   // per-project rule wins, verbatim
        }
        return switch (src) {
            case "decimal", "numeric" -> "NUMERIC";
            case "char", "nchar" -> sizedOrText("CHAR", size);
            case "varchar", "nvarchar" -> sizedOrText("VARCHAR", size);
            default -> DEFAULT_TYPES.getOrDefault(src, "TEXT");
        };
    }

    private String sizedOrText(String base, int size) {
        if (size <= 0 || size > MAX_VARCHAR) return "TEXT";
        return base + "(" + size + ")";
    }

    private String detectSemantic(String src, String name) {
        if ("uniqueidentifier".equals(src)) return "UUID";
        boolean stringLike = src.contains("char") || src.equals("text") || src.equals("ntext") || src.equals("xml");
        if (stringLike && !src.equals("xml") && JSON_NAME.matcher(name).matches()) return "JSON";
        return "NONE";
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> resolveOverrides(UUID projectId) {
        if (projectId == null) return Map.of();
        return projects.findById(projectId)
                .map(MigrationProject::getConfig)
                .map(cfg -> cfg.get("typeMappingOverrides"))
                .filter(v -> v instanceof Map)
                .map(v -> (Map<String, String>) v)
                .orElse(Map.of());
    }
}

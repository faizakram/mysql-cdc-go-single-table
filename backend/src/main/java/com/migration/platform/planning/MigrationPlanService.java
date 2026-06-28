package com.migration.platform.planning;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.JdbcSupport;
import com.migration.platform.connection.SchemaDiscoveryService;
import com.migration.platform.connection.TypeMappingMatrix;
import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ConstraintDtos.ForeignKeyInfo;
import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import com.migration.platform.planning.dto.PlanDtos.TableInput;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds an intelligent migration plan for a project (#88): dependency-ordered tables with parallel
 * levels (#89), row/storage/duration estimates (#90), and risk analysis (#91). Reads the source DB
 * for FKs, counts and column types; delegates ordering/risk assembly to {@link MigrationPlanLogic}.
 */
@Service
public class MigrationPlanService {

    private static final Logger log = LoggerFactory.getLogger(MigrationPlanService.class);
    private static final long ROWS_PER_SEC = 5_000;   // conservative default for the duration estimate
    private static final int LIGHTWEIGHT_TABLE_THRESHOLD = 50;  // above this, skip per-table FK/column discovery
    private static final int QUERY_TIMEOUT_SEC = 10;  // cap each catalog query so a slow source can't hang the dry-run

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final SchemaDiscoveryService discovery;
    private final JdbcSupport jdbc;
    private final CryptoService crypto;

    public MigrationPlanService(ProjectRepository projects, ConnectionRepository connections,
                                SchemaDiscoveryService discovery, JdbcSupport jdbc, CryptoService crypto) {
        this.projects = projects;
        this.connections = connections;
        this.discovery = discovery;
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @SuppressWarnings("unchecked")
    public MigrationPlan plan(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        DbConnection src = connections.findById(p.getSourceConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Project has no source connection"));
        DbType srcEngine = src.getDbType();
        DbType tgtEngine = connections.findById(p.getTargetConnectionId())
                .map(DbConnection::getDbType).orElse(DbType.POSTGRESQL);

        List<String> selected = selectedTables(p);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No tables selected for this project; pick tables first");
        }
        Set<String> selectedSet = new HashSet<>(selected);

        Map<String, Set<String>> deps = new HashMap<>();
        List<TableInput> inputs = new ArrayList<>();

        // Row counts come from catalog statistics (estimates), never COUNT(*) — an exact count is a full
        // table scan that takes minutes on billion-row tables and made the dry-run time out. For large
        // schemas we also skip per-table FK/column discovery (each opens its own JDBC connection — N+1):
        // the plan stays summary-level (tables + estimated rows + size) so it returns in seconds at any scale.
        boolean lightweight = selected.size() > LIGHTWEIGHT_TABLE_THRESHOLD;

        try (Connection conn = jdbc.open(src, crypto.decrypt(src.getPasswordEnc()))) {
            for (String fq : selected) {
                String[] parts = fq.split("\\.", 2);
                String schema = parts.length == 2 ? parts[0] : defaultSchema(srcEngine);
                String table = parts.length == 2 ? parts[1] : parts[0];

                boolean hasPk = true;
                int unmappable = 0;
                Set<String> parents = new HashSet<>();
                if (!lightweight) {
                    // FK dependencies (parent tables within the selected set).
                    for (ForeignKeyInfo fk : discovery.listForeignKeys(src.getId(), schema, table)) {
                        String parentFq = schema + "." + fk.refTable();
                        if (selectedSet.contains(parentFq)) parents.add(parentFq);
                    }
                    List<ColumnInfo> cols = discovery.listColumns(src.getId(), schema, table);
                    hasPk = cols.stream().anyMatch(ColumnInfo::primaryKey);
                    if (srcEngine != tgtEngine) {
                        for (ColumnInfo c : cols) {
                            if (TypeMappingMatrix.map(srcEngine, tgtEngine, c.dataType(), c.size()).note() != null) unmappable++;
                        }
                    }
                }
                deps.put(fq, parents);

                long rows = estimateRows(conn, srcEngine, schema, table);
                long bytes = sizeBytes(conn, srcEngine, schema, table);
                inputs.add(new TableInput(fq, Math.max(rows, 0), bytes, hasPk, unmappable));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Plan generation failed: " + e.getMessage());
        }
        return MigrationPlanLogic.plan(inputs, deps, ROWS_PER_SEC);
    }

    /**
     * Estimated row count from catalog statistics — fast and scan-free, unlike COUNT(*). Returns -1 when
     * unavailable (e.g. stats not gathered) so the caller treats it as unknown rather than zero-cost.
     */
    private long estimateRows(Connection conn, DbType engine, String schema, String table) {
        String lit = literal(table);
        String q = switch (engine) {
            case SQLSERVER -> "SELECT SUM(p.rows) FROM sys.partitions p "
                    + "WHERE p.object_id = OBJECT_ID('" + schema + "." + table + "') AND p.index_id IN (0,1)";
            case POSTGRESQL -> "SELECT c.reltuples::bigint FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = " + literal(schema) + " AND c.relname = " + lit;
            case MYSQL -> "SELECT table_rows FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = " + lit;
            case ORACLE -> "SELECT num_rows FROM all_tables WHERE owner = UPPER(" + literal(schema) + ") "
                    + "AND table_name = UPPER(" + lit + ")";
            case DB2 -> "SELECT card FROM syscat.tables WHERE tabschema = UPPER(" + literal(schema) + ") "
                    + "AND tabname = UPPER(" + lit + ")";
            default -> null;
        };
        if (q == null) return -1;
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(q)) {
                if (!rs.next()) return -1;
                long v = rs.getLong(1);
                return rs.wasNull() ? -1 : v;   // catalog stats can be NULL / -1 when never gathered
            }
        } catch (Exception e) {
            log.debug("row estimate failed for {}.{}: {}", schema, table, e.getMessage());
            return -1;
        }
    }

    /** SQL string literal with embedded single-quotes doubled (source DB the user owns; defends against quotes). */
    private String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private long sizeBytes(Connection conn, DbType engine, String schema, String table) {
        String q = switch (engine) {
            case POSTGRESQL -> "SELECT pg_total_relation_size('" + schema + "." + table + "')";
            case SQLSERVER -> "SELECT SUM(a.total_pages) * 8192 FROM sys.tables t "
                    + "JOIN sys.indexes i ON t.object_id=i.object_id "
                    + "JOIN sys.partitions pa ON i.object_id=pa.object_id AND i.index_id=pa.index_id "
                    + "JOIN sys.allocation_units a ON pa.partition_id=a.container_id "
                    + "WHERE t.name='" + table + "'";
            default -> null;   // best-effort: unknown for other engines (estimate from rows)
        };
        if (q == null) return 0;
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(q)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private String defaultSchema(DbType engine) {
        return switch (engine) { case SQLSERVER -> "dbo"; case POSTGRESQL -> "public"; default -> ""; };
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        return List.of();
    }
}

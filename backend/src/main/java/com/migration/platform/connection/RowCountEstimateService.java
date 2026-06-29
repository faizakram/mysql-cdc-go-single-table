package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast, scan-free source row-count estimates from catalog statistics (#185) — the same approach the
 * dry-run uses, lifted into a shared service so job start can capture a per-table total for snapshot
 * %-complete / ETA without an expensive {@code COUNT(*)}.
 */
@Service
public class RowCountEstimateService {

    private static final Logger log = LoggerFactory.getLogger(RowCountEstimateService.class);
    private static final int QUERY_TIMEOUT_SEC = 10;

    private final JdbcSupport jdbc;
    private final CryptoService crypto;

    public RowCountEstimateService(JdbcSupport jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /**
     * Estimate the row count of each {@code schema.table} on the source, keyed by lowercased table name.
     * Best-effort: tables whose estimate is unavailable (stats never gathered, non-relational source) are
     * simply omitted, so the caller treats them as "unknown total". Opens one pooled connection.
     */
    public Map<String, Long> estimate(DbConnection src, List<String> qualifiedTables) {
        Map<String, Long> out = new HashMap<>();
        if (src.getDbType() == DbType.MONGODB || qualifiedTables == null || qualifiedTables.isEmpty()) {
            return out;   // no JDBC catalog to read
        }
        try (Connection conn = jdbc.open(src, crypto.decrypt(src.getPasswordEnc()))) {
            for (String fq : qualifiedTables) {
                String[] parts = fq.split("\\.", 2);
                String schema = parts.length == 2 ? parts[0] : defaultSchema(src.getDbType());
                String table = parts.length == 2 ? parts[1] : parts[0];
                long est = estimateRows(conn, src.getDbType(), schema, table);
                if (est >= 0) out.put(table.toLowerCase(), est);
            }
        } catch (Exception e) {
            log.debug("Row-count estimate batch failed for connection {}: {}", src.getId(), e.getMessage());
        }
        return out;
    }

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

    private String defaultSchema(DbType t) {
        return t == DbType.SQLSERVER ? "dbo" : "public";
    }

    /** SQL string literal with embedded single-quotes doubled. */
    private String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}

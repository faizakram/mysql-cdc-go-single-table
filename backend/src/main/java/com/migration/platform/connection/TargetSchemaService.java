package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Ensures the target schema exists before a migration writes to it. The Debezium JDBC sink
 * auto-creates target <em>tables</em> but not the <em>schema</em>, so a project whose target schema
 * is missing fails silently at the sink. This service creates the schema on job start and powers a
 * dry-run pre-check. Only engines with a managed-schema concept we can safely create are handled
 * (PostgreSQL, SQL Server, Db2); MySQL (schema == database) and Oracle (schema == user) are no-ops.
 */
@Service
public class TargetSchemaService {

    private static final Logger log = LoggerFactory.getLogger(TargetSchemaService.class);

    private final JdbcSupport jdbc;
    private final CryptoService crypto;

    public TargetSchemaService(JdbcSupport jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /** Whether {@code schema} exists on the target. Returns true (nothing to do) for unmanaged engines or on probe failure. */
    public boolean exists(DbConnection target, String schema) {
        if (schema == null || schema.isBlank() || !managed(target.getDbType())) return true;
        try (Connection c = jdbc.open(target, crypto.decrypt(target.getPasswordEnc()))) {
            return present(c, target.getDbType(), schema);
        } catch (Exception e) {
            log.debug("Target schema existence check failed for '{}': {}", schema, e.getMessage());
            return true; // never block a dry-run on a probe error
        }
    }

    /** Create {@code schema} if missing. Returns true if it was created. No-op for unmanaged engines. */
    public boolean ensure(DbConnection target, String schema) {
        if (schema == null || schema.isBlank() || !managed(target.getDbType())) return false;
        DbType t = target.getDbType();
        try (Connection c = jdbc.open(target, crypto.decrypt(target.getPasswordEnc()))) {
            if (present(c, t, schema)) return false;
            try (Statement st = c.createStatement()) {
                st.execute(createDdl(t, schema));
            }
            log.info("Auto-created target schema '{}' on {}", schema, t);
            return true;
        } catch (Exception e) {
            log.warn("Could not auto-create target schema '{}' on {}: {}", schema, t, e.getMessage());
            return false;
        }
    }

    private boolean managed(DbType t) {
        return t == DbType.POSTGRESQL || t == DbType.SQLSERVER || t == DbType.DB2;
    }

    private boolean present(Connection c, DbType t, String schema) throws Exception {
        String q = switch (t) {
            case POSTGRESQL -> "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?";
            case SQLSERVER  -> "SELECT 1 FROM sys.schemas WHERE name = ?";
            case DB2        -> "SELECT 1 FROM syscat.schemata WHERE schemaname = ?";
            default -> null;
        };
        if (q == null) return true;
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, t == DbType.DB2 ? schema.toUpperCase() : schema);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    // CREATE statements are guarded by present(); SQL Server/Db2 lack CREATE SCHEMA IF NOT EXISTS.
    static String createDdl(DbType t, String schema) {
        String id = quote(t, schema);
        return switch (t) {
            case POSTGRESQL -> "CREATE SCHEMA IF NOT EXISTS " + id;
            case SQLSERVER, DB2 -> "CREATE SCHEMA " + id;
            default -> throw new IllegalStateException("unmanaged engine: " + t);
        };
    }

    /** Quote a schema identifier safely (doubles embedded quotes / brackets) to prevent injection. */
    static String quote(DbType t, String schema) {
        return t == DbType.SQLSERVER
                ? "[" + schema.replace("]", "]]") + "]"
                : "\"" + schema.replace("\"", "\"\"") + "\"";
    }
}

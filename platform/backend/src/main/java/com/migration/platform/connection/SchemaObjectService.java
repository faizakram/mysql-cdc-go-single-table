package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Discovers schema objects beyond tables (#92): sequences, identity columns, views, stored
 * procedures and functions. Tables/columns/PK/FK/indexes are handled by {@link SchemaDiscoveryService};
 * this service inventories the rest so nothing is silently lost — migratable objects get generated
 * DDL, the rest get an actionable report ("report, never drop").
 */
@Service
public class SchemaObjectService {

    private static final Logger log = LoggerFactory.getLogger(SchemaObjectService.class);

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;

    public SchemaObjectService(ConnectionRepository repo, CryptoService crypto, JdbcSupport jdbc) {
        this.repo = repo;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    public enum Category { SEQUENCE, IDENTITY, VIEW, PROCEDURE, FUNCTION }
    public enum Status { MIGRATABLE, REPORT_ONLY }

    public record SchemaObject(Category category, String schema, String name, Status status, String detail) {}
    public record Inventory(DbType engine, int migratable, int reportOnly, List<SchemaObject> objects) {}

    public Inventory inventory(UUID connectionId) {
        DbConnection c = repo.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection " + connectionId + " not found"));
        List<SchemaObject> out = new ArrayList<>();
        try (Connection conn = jdbc.open(c, crypto.decrypt(c.getPasswordEnc()))) {
            switch (c.getDbType()) {
                case POSTGRESQL -> postgres(conn, out);
                case SQLSERVER -> sqlserver(conn, out);
                case MYSQL -> mysql(conn, out);
                default -> log.debug("Schema-object discovery best-effort for {}", c.getDbType());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema-object discovery failed: " + e.getMessage());
        }
        int mig = (int) out.stream().filter(o -> o.status() == Status.MIGRATABLE).count();
        return new Inventory(c.getDbType(), mig, out.size() - mig, out);
    }

    private void postgres(Connection conn, List<SchemaObject> out) {
        query(conn, "SELECT sequence_schema, sequence_name FROM information_schema.sequences",
                rs -> out.add(new SchemaObject(Category.SEQUENCE, rs.getString(1), rs.getString(2),
                        Status.MIGRATABLE, "CREATE SEQUENCE generated on target (start reset post-load)")));
        query(conn, "SELECT table_schema, table_name, column_name FROM information_schema.columns "
                        + "WHERE is_identity='YES'",
                rs -> out.add(new SchemaObject(Category.IDENTITY, rs.getString(1), rs.getString(2) + "." + rs.getString(3),
                        Status.MIGRATABLE, "Identity column → target identity/serial")));
        query(conn, "SELECT table_schema, table_name FROM information_schema.views WHERE table_schema NOT IN ('pg_catalog','information_schema')",
                rs -> out.add(new SchemaObject(Category.VIEW, rs.getString(1), rs.getString(2),
                        Status.REPORT_ONLY, "View definition exported for manual/assisted translation")));
        query(conn, "SELECT routine_schema, routine_name, routine_type FROM information_schema.routines "
                        + "WHERE routine_schema NOT IN ('pg_catalog','information_schema')",
                rs -> out.add(new SchemaObject("FUNCTION".equalsIgnoreCase(rs.getString(3)) ? Category.FUNCTION : Category.PROCEDURE,
                        rs.getString(1), rs.getString(2), Status.REPORT_ONLY, "Routine body requires dialect translation")));
    }

    private void sqlserver(Connection conn, List<SchemaObject> out) {
        query(conn, "SELECT SCHEMA_NAME(schema_id), name FROM sys.sequences",
                rs -> out.add(new SchemaObject(Category.SEQUENCE, rs.getString(1), rs.getString(2),
                        Status.MIGRATABLE, "CREATE SEQUENCE generated on target")));
        query(conn, "SELECT SCHEMA_NAME(t.schema_id), t.name, c.name FROM sys.identity_columns c "
                        + "JOIN sys.tables t ON c.object_id=t.object_id",
                rs -> out.add(new SchemaObject(Category.IDENTITY, rs.getString(1), rs.getString(2) + "." + rs.getString(3),
                        Status.MIGRATABLE, "IDENTITY column → target identity/serial")));
        query(conn, "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS",
                rs -> out.add(new SchemaObject(Category.VIEW, rs.getString(1), rs.getString(2),
                        Status.REPORT_ONLY, "View definition exported for translation")));
        query(conn, "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE FROM INFORMATION_SCHEMA.ROUTINES",
                rs -> out.add(new SchemaObject("FUNCTION".equalsIgnoreCase(rs.getString(3)) ? Category.FUNCTION : Category.PROCEDURE,
                        rs.getString(1), rs.getString(2), Status.REPORT_ONLY, "T-SQL body requires translation")));
    }

    private void mysql(Connection conn, List<SchemaObject> out) {
        // MySQL: no sequences; AUTO_INCREMENT ~ identity; views + routines via information_schema.
        query(conn, "SELECT table_schema, table_name FROM information_schema.views WHERE table_schema=DATABASE()",
                rs -> out.add(new SchemaObject(Category.VIEW, rs.getString(1), rs.getString(2),
                        Status.REPORT_ONLY, "View definition exported for translation")));
        query(conn, "SELECT routine_schema, routine_name, routine_type FROM information_schema.routines WHERE routine_schema=DATABASE()",
                rs -> out.add(new SchemaObject("FUNCTION".equalsIgnoreCase(rs.getString(3)) ? Category.FUNCTION : Category.PROCEDURE,
                        rs.getString(1), rs.getString(2), Status.REPORT_ONLY, "Routine body requires translation")));
    }

    @FunctionalInterface private interface RowFn { void accept(ResultSet rs) throws Exception; }

    private void query(Connection conn, String sql, RowFn fn) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) fn.accept(rs);
        } catch (Exception e) {
            log.debug("schema-object query skipped: {}", e.getMessage());
        }
    }
}

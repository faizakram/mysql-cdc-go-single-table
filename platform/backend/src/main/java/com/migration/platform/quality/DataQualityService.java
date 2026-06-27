package com.migration.platform.quality;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.JdbcSupport;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Column profiling + PII flagging (#113/#114). Profiles each column (null %, distinct, min/max) and
 * flags likely PII so it can be masked during migration. Best-effort per engine.
 */
@Service
public class DataQualityService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;

    public DataQualityService(ConnectionRepository repo, CryptoService crypto, JdbcSupport jdbc) {
        this.repo = repo;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    public record ColumnProfile(String column, String type, long nulls, double nullPct,
                                long distinct, String min, String max, String pii) {}
    public record TableProfile(String schema, String table, long rows, List<ColumnProfile> columns) {}

    public TableProfile profile(UUID connectionId, String schema, String table) {
        DbConnection c = repo.findById(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection " + connectionId + " not found"));
        DbType engine = c.getDbType();
        String fq = qualify(engine, schema, table);
        List<ColumnProfile> cols = new ArrayList<>();
        long rows;
        try (Connection conn = jdbc.open(c, crypto.decrypt(c.getPasswordEnc()))) {
            rows = scalar(conn, "SELECT COUNT(*) FROM " + fq);
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), schema, table, "%")) {
                while (rs.next()) { names.add(rs.getString("COLUMN_NAME")); types.add(rs.getString("TYPE_NAME")); }
            }
            for (int i = 0; i < names.size(); i++) {
                String col = names.get(i);
                String colRef = quote(engine, col);
                long nulls = scalar(conn, "SELECT COUNT(*) FROM " + fq + " WHERE " + colRef + " IS NULL");
                long distinct = scalar(conn, "SELECT COUNT(DISTINCT " + colRef + ") FROM " + fq);
                String sample = sampleValue(conn, fq, colRef);
                String min = strScalar(conn, "SELECT MIN(CAST(" + colRef + " AS " + textType(engine) + ")) FROM " + fq);
                String max = strScalar(conn, "SELECT MAX(CAST(" + colRef + " AS " + textType(engine) + ")) FROM " + fq);
                double pct = rows == 0 ? 0 : Math.round((nulls * 10000.0 / rows)) / 100.0;
                cols.add(new ColumnProfile(col, types.get(i), nulls, pct, distinct, min, max,
                        Pii.detect(col, sample).name()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Profiling failed: " + e.getMessage());
        }
        return new TableProfile(schema, table, rows, cols);
    }

    private String sampleValue(Connection conn, String fq, String colRef) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + colRef + " FROM " + fq + " WHERE " + colRef + " IS NOT NULL")) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : null;
        } catch (Exception e) { return null; }
    }

    private long scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) { return 0; }
    }
    private String strScalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { return null; }
    }
    private String textType(DbType e) { return e == DbType.SQLSERVER ? "varchar(4000)" : "text"; }
    private String quote(DbType e, String id) { return e == DbType.SQLSERVER ? "[" + id + "]" : "\"" + id + "\""; }
    private String qualify(DbType e, String schema, String table) {
        return switch (e) {
            case SQLSERVER -> "[" + schema + "].[" + table + "]";
            case MYSQL -> "`" + table + "`";
            default -> "\"" + schema + "\".\"" + table + "\"";
        };
    }
}

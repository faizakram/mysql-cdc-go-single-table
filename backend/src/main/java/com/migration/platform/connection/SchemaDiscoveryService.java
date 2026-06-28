package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ConstraintDtos.ForeignKeyInfo;
import com.migration.platform.connection.dto.ConstraintDtos.IndexInfo;
import com.migration.platform.connection.dto.TableInfo;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Introspects a source/target database so the UI can drive table selection and mapping (issue #30).
 * Uses portable JDBC {@link DatabaseMetaData} for tables/columns/PKs; CDC-enabled detection is
 * SQL Server specific (cdc.change_tables).
 */
@Service
public class SchemaDiscoveryService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;
    private final MongoSupport mongo;

    public SchemaDiscoveryService(ConnectionRepository repo, CryptoService crypto, JdbcSupport jdbc,
                                  MongoSupport mongo) {
        this.repo = repo;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.mongo = mongo;
    }

    public List<TableInfo> listTables(UUID connectionId, String schemaFilter) {
        DbConnection c = find(connectionId);
        if (c.getDbType() == DbType.MONGODB) {   // collections, via the native driver (#124)
            return mongo.listCollections(c, crypto.decrypt(c.getPasswordEnc()));
        }
        String schema = effectiveSchema(c, schemaFilter);
        try (Connection conn = open(c)) {
            String catalog = conn.getCatalog();
            DatabaseMetaData md = conn.getMetaData();
            Set<String> cdc = c.getDbType() == DbType.SQLSERVER ? cdcEnabledTables(conn) : Set.of();
            // Primary-key flags in ONE catalog query instead of a getPrimaryKeys() call per table —
            // the per-table calls were an N+1 that's painfully slow over a high-latency link (#).
            // null = couldn't resolve set-based → fall back to the per-table metadata lookup.
            Set<String> pkTables = primaryKeyTables(conn, c.getDbType());

            List<TableInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String sch = rs.getString("TABLE_SCHEM");
                    String tbl = rs.getString("TABLE_NAME");
                    boolean hasPk = pkTables != null
                            ? pkTables.contains((sch + "." + tbl).toLowerCase())
                            : hasPrimaryKey(md, catalog, sch, tbl);
                    out.add(new TableInfo(sch, tbl, hasPk, cdc.contains((sch + "." + tbl).toLowerCase())));
                }
            }
            out.sort(Comparator.comparing(TableInfo::tableName, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (SQLException e) {
            throw new IllegalArgumentException("Schema discovery failed: " + e.getMessage());
        }
    }

    public List<ColumnInfo> listColumns(UUID connectionId, String schema, String table) {
        DbConnection c = find(connectionId);
        if (c.getDbType() == DbType.MONGODB) {   // infer fields from a sample document (#124)
            return mongo.sampleColumns(c, crypto.decrypt(c.getPasswordEnc()), table);
        }
        try (Connection conn = open(c)) {
            String catalog = conn.getCatalog();
            DatabaseMetaData md = conn.getMetaData();
            Set<String> pks = primaryKeyColumns(md, catalog, schema, table);

            List<ColumnInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    out.add(new ColumnInfo(
                            name,
                            rs.getString("TYPE_NAME"),
                            rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                            pks.contains(name)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalArgumentException("Column discovery failed: " + e.getMessage());
        }
    }

    /** Secondary indexes on a table (excludes the primary-key index). */
    public List<IndexInfo> listIndexes(UUID connectionId, String schema, String table) {
        DbConnection c = find(connectionId);
        try (Connection conn = open(c)) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = conn.getCatalog();
            Set<String> pk = primaryKeyColumns(md, catalog, schema, table);

            Map<String, boolean[]> uniqueByIdx = new LinkedHashMap<>();
            Map<String, List<String>> colsByIdx = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(catalog, schema, table, false, false)) {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    String name = rs.getString("INDEX_NAME");
                    String col = rs.getString("COLUMN_NAME");
                    if (name == null || col == null) continue;
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    uniqueByIdx.computeIfAbsent(name, k -> new boolean[]{unique});
                    colsByIdx.computeIfAbsent(name, k -> new ArrayList<>()).add(col);
                }
            }
            List<IndexInfo> out = new ArrayList<>();
            for (var e : colsByIdx.entrySet()) {
                if (!pk.isEmpty() && new HashSet<>(e.getValue()).equals(pk)) continue; // PK index already exists
                out.add(new IndexInfo(e.getKey(), uniqueByIdx.get(e.getKey())[0], e.getValue()));
            }
            return out;
        } catch (SQLException ex) {
            throw new IllegalArgumentException("Index discovery failed: " + ex.getMessage());
        }
    }

    /** Foreign keys declared on a table. */
    public List<ForeignKeyInfo> listForeignKeys(UUID connectionId, String schema, String table) {
        DbConnection c = find(connectionId);
        try (Connection conn = open(c)) {
            DatabaseMetaData md = conn.getMetaData();
            Map<String, String> refTable = new LinkedHashMap<>();
            Map<String, List<String>> fkCols = new LinkedHashMap<>();
            Map<String, List<String>> refCols = new LinkedHashMap<>();
            try (ResultSet rs = md.getImportedKeys(conn.getCatalog(), schema, table)) {
                while (rs.next()) {
                    String name = rs.getString("FK_NAME");
                    if (name == null) name = "fk_" + table + "_" + rs.getString("FKCOLUMN_NAME");
                    refTable.putIfAbsent(name, rs.getString("PKTABLE_NAME"));
                    fkCols.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("FKCOLUMN_NAME"));
                    refCols.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("PKCOLUMN_NAME"));
                }
            }
            List<ForeignKeyInfo> out = new ArrayList<>();
            for (String name : refTable.keySet()) {
                out.add(new ForeignKeyInfo(name, fkCols.get(name), refTable.get(name), refCols.get(name)));
            }
            return out;
        } catch (SQLException ex) {
            throw new IllegalArgumentException("Foreign-key discovery failed: " + ex.getMessage());
        }
    }

    private Connection open(DbConnection c) throws SQLException {
        return jdbc.open(c, crypto.decrypt(c.getPasswordEnc()));
    }

    private String effectiveSchema(DbConnection c, String schemaFilter) {
        if (schemaFilter != null && !schemaFilter.isBlank()) return schemaFilter;
        return switch (c.getDbType()) {
            case SQLSERVER -> "dbo";
            case POSTGRESQL -> "public";
            case ORACLE -> c.getUsername() == null ? null : c.getUsername().toUpperCase();
            // MySQL/Db2 expose tables under the catalog (database); MongoDB has no relational schema.
            case MYSQL, DB2, MONGODB -> null;
        };
    }

    private boolean hasPrimaryKey(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            return rs.next();
        }
    }

    private Set<String> primaryKeyColumns(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
        }
        return pks;
    }

    /**
     * Tables that have a primary key, as "schema.table" (lowercased), fetched in a single catalog
     * query per engine — avoids a getPrimaryKeys() round-trip per table when listing. Returns null
     * if the set can't be resolved (unknown engine / query failed) so the caller falls back per-table.
     */
    private Set<String> primaryKeyTables(Connection conn, DbType engine) {
        String sql = switch (engine) {
            case SQLSERVER, POSTGRESQL, MYSQL ->
                    "SELECT table_schema, table_name FROM information_schema.table_constraints "
                            + "WHERE constraint_type = 'PRIMARY KEY'";
            case ORACLE -> "SELECT owner, table_name FROM all_constraints WHERE constraint_type = 'P'";
            case DB2 -> "SELECT tabschema, tabname FROM syscat.tabconst WHERE type = 'P'";
            case MONGODB -> null;
        };
        if (sql == null) return null;
        Set<String> set = new HashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) set.add((rs.getString(1) + "." + rs.getString(2)).toLowerCase());
            return set;
        } catch (SQLException e) {
            return null;   // permission/engine quirk — caller uses the per-table metadata lookup
        }
    }

    /** SQL Server: tables tracked by CDC. Returns "schema.table" (lowercased). Empty if CDC absent. */
    private Set<String> cdcEnabledTables(Connection conn) {
        String sql = """
                SELECT s.name AS schema_name, t.name AS table_name
                FROM cdc.change_tables ct
                JOIN sys.tables t ON ct.source_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                """;
        Set<String> set = new HashSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                set.add((rs.getString("schema_name") + "." + rs.getString("table_name")).toLowerCase());
            }
        } catch (SQLException ignored) {
            // CDC schema not present / not permitted — treat as none enabled.
        }
        return set;
    }

    private DbConnection find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Connection " + id + " not found"));
    }
}

package com.migration.platform.bulkload;

import com.migration.platform.common.CryptoService;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.JdbcSupport;
import com.migration.platform.connection.TableDdlBuilder;
import com.migration.platform.connection.TargetSchemaService;
import com.migration.platform.connection.TypeMappingService;
import com.migration.platform.connection.dto.ColumnMapping;
import com.migration.platform.connector.NamingStrategy;
import com.migration.platform.connector.TargetNaming;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.job.JobRepository;
import com.migration.platform.job.JobStatus;
import com.migration.platform.job.TableStatus;
import com.migration.platform.job.TableStatusRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CDC-free full load: a direct JDBC bulk copy that reads each selected source table and creates +
 * populates the matching target table, with no Debezium, Kafka, or CDC involved (#191).
 *
 * <p>This is the auto-fallback path {@code JobService} takes when the source isn't CDC-ready: rather
 * than blocking the job, it performs a one-time full load so a migration still completes. Runs off the
 * request thread on a small executor; progress and terminal status are written to the job and its
 * per-table status rows so the existing UI reflects it. Re-runnable: each table is dropped and
 * recreated (full-load = replace), so a restart yields a clean target.
 */
@Service
public class BulkCopyService {

    private static final Logger log = LoggerFactory.getLogger(BulkCopyService.class);
    private static final int BATCH = 1000;

    private final JobRepository jobs;
    private final ConnectionRepository connections;
    private final TableStatusRepository tableStatus;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;
    private final TypeMappingService typeMapping;
    private final TableDdlBuilder ddl;
    private final TargetSchemaService targetSchema;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "bulk-copy");
        t.setDaemon(true);
        return t;
    });

    public BulkCopyService(JobRepository jobs, ConnectionRepository connections,
                           TableStatusRepository tableStatus, CryptoService crypto, JdbcSupport jdbc,
                           TypeMappingService typeMapping, TableDdlBuilder ddl, TargetSchemaService targetSchema) {
        this.jobs = jobs;
        this.connections = connections;
        this.tableStatus = tableStatus;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.typeMapping = typeMapping;
        this.ddl = ddl;
        this.targetSchema = targetSchema;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** Marker for a copy aborted because the job was stopped. */
    private static final class Cancelled extends RuntimeException {}

    public record BulkCopyRequest(
            UUID jobId, UUID projectId, UUID sourceConnectionId, UUID targetConnectionId,
            String targetSchema, NamingStrategy naming, List<String> tables, int fetchSize) {}

    /** Kick off the full load on a background thread; returns immediately. */
    public void startAsync(BulkCopyRequest req) {
        pool.submit(() -> {
            try {
                execute(req);
            } catch (Cancelled c) {
                log.info("Bulk copy for job {} cancelled (job stopped)", req.jobId());
            } catch (Exception e) {
                log.warn("Bulk copy for job {} failed: {}", req.jobId(), e.getMessage(), e);
                failJob(req.jobId(), e.getMessage());
            }
        });
    }

    private void execute(BulkCopyRequest req) throws SQLException {
        DbConnection src = connections.findById(req.sourceConnectionId()).orElseThrow();
        DbConnection tgt = connections.findById(req.targetConnectionId()).orElseThrow();
        DbType srcType = src.getDbType();
        DbType tgtType = tgt.getDbType();

        // MongoDB isn't reached over JDBC, so it can't be bulk-copied; it requires the CDC/change-streams
        // path. Fail clearly instead of crashing on jdbc.open.
        if (srcType == DbType.MONGODB) {
            throw new IllegalStateException(
                    "MongoDB source requires the CDC/change-streams path; CDC-free bulk copy is not supported for MongoDB.");
        }

        setJob(req.jobId(), JobStatus.RUNNING, "full-load", null, false);
        log.info("Bulk copy job {}: {} table(s) {} → {}", req.jobId(), req.tables().size(), srcType, tgtType);

        // Create the target schema if it doesn't exist (the Debezium JDBC sink does this on the CDC
        // path; the bulk path must do it too before CREATE TABLE).
        targetSchema.ensure(tgt, req.targetSchema());

        long totalRows = 0;
        int done = 0, skipped = 0;
        List<String> failures = new java.util.ArrayList<>();   // table-level fault isolation (#217)
        try (Connection sc = jdbc.open(src, crypto.decrypt(src.getPasswordEnc()));
             Connection tc = jdbc.open(tgt, crypto.decrypt(tgt.getPasswordEnc()))) {
            tc.setAutoCommit(false);
            for (String fq : req.tables()) {
                if (isStopped(req.jobId())) throw new Cancelled();
                String[] parts = fq.split("\\.", 2);
                String schemaName = parts.length == 2 ? parts[0] : defaultSchema(srcType, src.getUsername());
                String tableName = parts.length == 2 ? parts[1] : parts[0];

                // Table-level resume (#217): a table already COMPLETED in a prior run keeps its target
                // data — skip it so a restart re-copies only the pending/failed tables, not everything.
                if (isCompleted(req.jobId(), tableName)) {
                    skipped++;
                    log.info("Bulk copy job {}: {}.{} already completed — skipping (resume)", req.jobId(), schemaName, tableName);
                    continue;
                }

                // Per-table fault isolation (#217): one table's failure must not abort the whole job
                // (DMS isolates per-table). Mark it FAILED, then carry on with the remaining tables.
                try {
                    totalRows += copyTable(req, sc, tc, srcType, tgtType, schemaName, tableName);
                    done++;
                } catch (Cancelled c) {
                    throw c;   // a stop is job-wide, not a table failure
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    failures.add(schemaName + "." + tableName + ": " + msg);
                    markTable(req.jobId(), schemaName, tableName, "FAILED", 0, msg);
                    log.warn("Bulk copy job {}: {}.{} failed — continuing with remaining tables: {}",
                            req.jobId(), schemaName, tableName, msg);
                    // A partial copy may have committed rows + left the target half-populated; the table is
                    // FAILED, so a restart drops + recreates it (copyTable is idempotent). Roll back any
                    // open batch on the shared target connection so the next table starts clean.
                    try { tc.rollback(); } catch (SQLException ignore) { /* connection may be unusable */ }
                }
            }
        }

        if (failures.isEmpty()) {
            setJob(req.jobId(), JobStatus.COMPLETED, "full-load", null, true);
            log.info("Bulk copy job {} completed: {} row(s) across {} table(s) ({} resumed/skipped)",
                    req.jobId(), totalRows, done, skipped);
        } else {
            // Some tables succeeded, some failed: surface a per-table summary and mark the job FAILED so the
            // failure is visible. Completed tables keep their data + COMPLETED status, so restarting the job
            // re-copies only the failed/pending tables (#217).
            setJob(req.jobId(), JobStatus.FAILED, "full-load", failureSummary(done + skipped, failures), true);
            log.warn("Bulk copy job {}: {} table(s) ok, {} failed — {}",
                    req.jobId(), done + skipped, failures.size(), failureSummary(done + skipped, failures));
        }
    }

    /** A table is resumable-skippable only once a prior run marked it fully COMPLETED. */
    private boolean isCompleted(UUID jobId, String tableName) {
        return tableStatus.findByJobIdAndTableNameIgnoreCase(jobId, tableName)
                .map(t -> "COMPLETED".equals(t.getStatus()))
                .orElse(false);
    }

    /** Compact job-level error for a partially-failed run: "{ok}/{total} tables ok; {n} failed: …". */
    static String failureSummary(int ok, List<String> failures) {
        int total = ok + failures.size();
        StringBuilder sb = new StringBuilder()
                .append(ok).append('/').append(total).append(" tables ok; ")
                .append(failures.size()).append(" failed: ");
        int show = Math.min(failures.size(), 5);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append("; ");
            sb.append(failures.get(i));
        }
        if (failures.size() > show) sb.append("; …(+").append(failures.size() - show).append(" more)");
        return sb.toString();
    }

    private long copyTable(BulkCopyRequest req, Connection sc, Connection tc, DbType srcType, DbType tgtType,
                           String schemaName, String tableName) throws SQLException {
        markTable(req.jobId(), schemaName, tableName, "IN_PROGRESS", 0, null);

        List<ColumnMapping> cols =
                typeMapping.proposeForTable(req.sourceConnectionId(), schemaName, tableName, req.projectId());
        if (cols.isEmpty()) {
            markTable(req.jobId(), schemaName, tableName, "FAILED", 0, "No columns found on source table");
            throw new IllegalStateException("No columns found for " + schemaName + "." + tableName);
        }

        String targetTable = TargetNaming.apply(tableName, req.naming());

        // Full-load = replace: drop any existing target table, then create it fresh.
        try (Statement st = tc.createStatement()) {
            try {
                st.execute(ddl.dropIfExists(tgtType, req.targetSchema(), targetTable));
            } catch (SQLException dropErr) {
                log.debug("drop {}.{} ignored: {}", req.targetSchema(), targetTable, dropErr.getMessage());
            }
            st.execute(ddl.createTable(tgtType, req.targetSchema(), targetTable, cols, req.naming()));
            tc.commit();
        }

        String srcCols = joinQuoted(srcType, cols, c -> c.column());
        String selectSql = "SELECT " + srcCols + " FROM " + TableDdlBuilder.qualified(srcType, schemaName, tableName);
        String tgtCols = joinQuoted(tgtType, cols, c -> TargetNaming.apply(c.column(), req.naming()));
        String placeholders = cols.stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElse("");
        String insertSql = "INSERT INTO " + TableDdlBuilder.qualified(tgtType, req.targetSchema(), targetTable)
                + " (" + tgtCols + ") VALUES (" + placeholders + ")";

        long rows = 0;
        try (Statement sel = sc.createStatement();
             PreparedStatement ins = tc.prepareStatement(insertSql)) {
            sel.setFetchSize(req.fetchSize());
            try (ResultSet rs = sel.executeQuery(selectSql)) {
                int inBatch = 0;
                while (rs.next()) {
                    for (int i = 0; i < cols.size(); i++) {
                        bind(ins, i + 1, rs.getObject(i + 1), cols.get(i), tgtType);
                    }
                    ins.addBatch();
                    rows++;
                    if (++inBatch >= BATCH) {
                        ins.executeBatch();
                        tc.commit();
                        inBatch = 0;
                        markTable(req.jobId(), schemaName, tableName, "IN_PROGRESS", rows, null);
                        if (isStopped(req.jobId())) throw new Cancelled();
                    }
                }
                ins.executeBatch();
                tc.commit();
            }
        }
        markTable(req.jobId(), schemaName, tableName, "COMPLETED", rows, null);
        log.info("Bulk copy job {}: {}.{} → {} rows", req.jobId(), schemaName, tableName, rows);
        return rows;
    }

    /** Bind one value, converting the cross-dialect cases that {@code setObject} can't handle directly. */
    private void bind(PreparedStatement ps, int idx, Object v, ColumnMapping col, DbType target) throws SQLException {
        boolean pg = target == DbType.POSTGRESQL;
        if (v == null) {
            // PostgreSQL infers a NULL's type from context and rejects a mismatched JDBC type (e.g. CHAR
            // null into a uuid column), so use Types.NULL there. The SQL Server driver does the opposite —
            // Types.NULL makes it declare the batch parameter as varbinary, which can't insert into
            // datetimeoffset/uuid/etc. — so give it the target column's JDBC type.
            ps.setNull(idx, pg ? Types.NULL : nullSqlType(col.proposedType()));
            return;
        }
        // JSON: PostgreSQL jsonb needs Types.OTHER; every other engine renders it as a text/char type.
        if ("JSON".equals(col.semantic())) {
            if (pg) ps.setObject(idx, v.toString(), Types.OTHER);
            else ps.setString(idx, v.toString());
            return;
        }
        // UUID — by semantic OR by value type (PostgreSQL returns its native uuid as java.util.UUID with
        // semantic NONE). PostgreSQL target takes a UUID object; every other target stores it as text.
        if ("UUID".equals(col.semantic()) || v instanceof UUID) {
            if (pg) {
                try { ps.setObject(idx, UUID.fromString(v.toString())); return; }
                catch (IllegalArgumentException ignore) { /* fall through to string */ }
            }
            ps.setString(idx, v.toString());
            return;
        }
        // Booleans: MySQL/Oracle/Db2 render BOOL as TINYINT(1)/NUMBER(1)/SMALLINT — bind as 0/1.
        if (v instanceof Boolean b
                && (target == DbType.MYSQL || target == DbType.ORACLE || target == DbType.DB2)) {
            ps.setInt(idx, b ? 1 : 0);
            return;
        }
        // SQL Server datetimeoffset surfaces as microsoft.sql.DateTimeOffset — unwrap to OffsetDateTime.
        if (v.getClass().getName().equals("microsoft.sql.DateTimeOffset")) {
            try {
                Object odt = v.getClass().getMethod("getOffsetDateTime").invoke(v);
                ps.setObject(idx, odt);
                return;
            } catch (ReflectiveOperationException e) {
                ps.setString(idx, v.toString());
                return;
            }
        }
        // Cross-engine targets: a value object from the source driver may be unintelligible to a
        // different target driver. Temporal values are the sharp edge — the SQL Server driver can't
        // coerce a plain string into datetimeoffset, so hand it a native java.time value instead.
        // PostgreSQL vendor objects (jsonb/json/etc.) bind as text. (PostgreSQL target keeps natives.)
        if (!pg) {
            boolean tz = col.proposedType() != null
                    && col.proposedType().toUpperCase().matches(".*(DATETIMEOFFSET|TIME ZONE|TIMESTAMPTZ).*");
            java.time.Instant instant = instantOf(v);
            if (instant != null) {
                if (tz && target == DbType.SQLSERVER) {
                    // The SQL Server driver only binds datetimeoffset reliably via its own
                    // microsoft.sql.DateTimeOffset type — strings/java.time serialize as varbinary.
                    ps.setObject(idx, sqlServerDateTimeOffset(instant));
                    return;
                }
                String s = tz ? instant.atOffset(java.time.ZoneOffset.UTC).format(TS_TZ)
                              : instant.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime().format(TS_LOCAL);
                ps.setString(idx, s);
                return;
            }
            String temporal = temporalText(v);   // DATE/TIME-only values
            if (temporal != null) { ps.setString(idx, temporal); return; }
            if (v.getClass().getName().startsWith("org.postgresql.")) { ps.setString(idx, v.toString()); return; }
        }
        // Generic path with a safety net: if the driver rejects the native object (an exotic vendor
        // type, e.g. oracle.sql.*), degrade to its string form rather than failing the whole table.
        try {
            ps.setObject(idx, v);
        } catch (SQLException e) {
            ps.setString(idx, v.toString());
        }
    }

    private static final java.time.format.DateTimeFormatter TS_TZ =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS xxx");
    private static final java.time.format.DateTimeFormatter TS_LOCAL =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS");

    /** Coarse JDBC type for a NULL bind, from the proposed target type — so the driver types the param. */
    private int nullSqlType(String proposedType) {
        String t = proposedType == null ? "" : proposedType.toUpperCase();
        if (t.contains("DATETIMEOFFSET") || t.contains("TIME ZONE") || t.contains("TIMESTAMPTZ"))
            return Types.TIMESTAMP_WITH_TIMEZONE;
        if (t.contains("TIMESTAMP") || t.contains("DATETIME")) return Types.TIMESTAMP;
        if (t.startsWith("DATE")) return Types.DATE;
        if (t.startsWith("TIME")) return Types.TIME;
        if (t.contains("UNIQUEIDENTIFIER") || t.contains("UUID")) return Types.CHAR;
        if (t.contains("INT")) return Types.INTEGER;
        if (t.contains("BIT") || t.contains("BOOL")) return Types.BOOLEAN;
        if (t.contains("NUMERIC") || t.contains("DECIMAL") || t.contains("NUMBER")) return Types.NUMERIC;
        if (t.contains("FLOAT") || t.contains("REAL") || t.contains("DOUBLE")) return Types.DOUBLE;
        if (t.contains("BINARY") || t.contains("BLOB") || t.contains("BYTEA")) return Types.VARBINARY;
        return Types.VARCHAR;
    }

    /** Instant for a date-time value (carrying an instant), else null (date-only / time-only / non-temporal). */
    private java.time.Instant instantOf(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (v instanceof java.time.Instant i) return i;
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }

    /** Build a {@code microsoft.sql.DateTimeOffset} (UTC) reflectively — the only datetimeoffset bind the driver accepts. */
    private Object sqlServerDateTimeOffset(java.time.Instant instant) {
        try {
            Class<?> cls = Class.forName("microsoft.sql.DateTimeOffset");
            return cls.getMethod("valueOf", java.sql.Timestamp.class, int.class)
                    .invoke(null, java.sql.Timestamp.from(instant), 0);
        } catch (ReflectiveOperationException e) {
            // Fallback: ISO string (server-side conversion).
            return instant.atOffset(java.time.ZoneOffset.UTC).format(TS_TZ);
        }
    }

    /** Render a date-only / time-only value as a server-parseable string, else null. */
    private String temporalText(Object v) {
        if (v instanceof java.sql.Date d) return d.toString();
        if (v instanceof java.time.LocalDate ld) return ld.toString();
        if (v instanceof java.sql.Time t) return t.toString();
        if (v instanceof java.time.LocalTime lt) return lt.toString();
        return null;
    }

    private String joinQuoted(DbType t, List<ColumnMapping> cols, java.util.function.Function<ColumnMapping, String> name) {
        return cols.stream().map(c -> TableDdlBuilder.quoteIdent(t, name.apply(c)))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** Default schema for an unqualified source table name, per engine. */
    private String defaultSchema(DbType t, String username) {
        return switch (t) {
            case SQLSERVER -> "dbo";
            case POSTGRESQL -> "public";
            // Oracle/Db2 default schema is the connecting user (uppercased).
            case ORACLE, DB2 -> username == null ? "public" : username.toUpperCase();
            // MySQL has no schema layer; qualified() omits the schema segment for MySQL anyway.
            default -> "public";
        };
    }

    // ---- status persistence (each call its own transaction; safe from the worker thread) ----

    private boolean isStopped(UUID jobId) {
        return jobs.findById(jobId).map(j -> j.getStatus() == JobStatus.STOPPED).orElse(true);
    }

    private void setJob(UUID jobId, JobStatus status, String phase, String error, boolean finished) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setStatus(status);
            j.setPhase(phase);
            j.setError(error);
            if (finished) j.setFinishedAt(OffsetDateTime.now());
            jobs.save(j);
        });
    }

    private void failJob(UUID jobId, String error) {
        setJob(jobId, JobStatus.FAILED, "full-load", error, true);
    }

    private void markTable(UUID jobId, String schemaName, String tableName, String status, long rows, String error) {
        TableStatus ts = tableStatus.findByJobIdAndTableNameIgnoreCase(jobId, tableName)
                .orElseGet(() -> {
                    TableStatus n = new TableStatus();
                    n.setJobId(jobId);
                    n.setSchemaName(schemaName);
                    n.setTableName(tableName);
                    return n;
                });
        ts.setPhase("DATA");
        ts.setStatus(status);
        ts.setRowsSynced(rows);
        ts.setError(error);
        tableStatus.save(ts);
    }
}

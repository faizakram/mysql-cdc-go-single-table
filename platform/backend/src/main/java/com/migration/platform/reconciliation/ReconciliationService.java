package com.migration.platform.reconciliation;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.JdbcSupport;
import com.migration.platform.connector.DeleteStrategy;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.reconciliation.dto.ReconciliationDtos.RunDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Data validation (issues #47, #48). Two modes:
 *  - COUNT: per-table source-vs-target row-count comparison (soft-delete aware).
 *  - CHECKSUM: sample primary keys from the source and verify their presence in the target,
 *    normalising key values to lower-cased text so they compare across the type conversion.
 *    Catches identity gaps that equal counts can hide. Single-PK tables only; others are SKIPPED.
 * Field-level content checksums (type/charset-normalised) are a deeper follow-up.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;
    private final ReconciliationRunRepository runRepo;
    private final ReconciliationResultRepository resultRepo;

    public ReconciliationService(ProjectRepository projects, ConnectionRepository connections,
                                 CryptoService crypto, JdbcSupport jdbc,
                                 ReconciliationRunRepository runRepo,
                                 ReconciliationResultRepository resultRepo) {
        this.projects = projects;
        this.connections = connections;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
    }

    @Transactional(readOnly = true)
    public List<RunDto> history(UUID projectId) {
        return runRepo.findByProjectIdOrderByStartedAtDesc(projectId).stream()
                .map(run -> RunDto.from(run, resultRepo.findByRunIdOrderByTableName(run.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RunDto report(UUID runId) {
        ReconciliationRun run = runRepo.findById(runId)
                .orElseThrow(() -> new NotFoundException("Reconciliation run " + runId + " not found"));
        return RunDto.from(run, resultRepo.findByRunIdOrderByTableName(runId));
    }

    @Transactional
    public RunDto run(UUID projectId, String mode, int sampleSize) {
        boolean checksum = "CHECKSUM".equalsIgnoreCase(mode);
        MigrationProject project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        DbConnection src = requireConnection(project.getSourceConnectionId(), "source");
        DbConnection tgt = requireConnection(project.getTargetConnectionId(), "target");
        MigrationConfig mc = MigrationConfig.from(project.getConfig(), project.getName());

        List<String> tables = selectedTables(project);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("No tables selected for this project; pick tables first");
        }

        ReconciliationRun run = new ReconciliationRun();
        run.setProjectId(projectId);
        run.setStatus("RUNNING");
        run.setMode(checksum ? "CHECKSUM" : "COUNT");
        run.setTotalTables(tables.size());
        run = runRepo.save(run);

        int mismatched = 0;
        boolean softDelete = mc.deleteStrategy() == DeleteStrategy.SOFT;

        try (Connection sc = jdbc.open(src, crypto.decrypt(src.getPasswordEnc()));
             Connection tc = jdbc.open(tgt, crypto.decrypt(tgt.getPasswordEnc()))) {
            for (String fq : tables) {
                String[] parts = fq.split("\\.", 2);
                String schema = parts.length == 2 ? parts[0] : "dbo";
                String tableName = parts.length == 2 ? parts[1] : parts[0];
                ReconciliationResult r = checksum
                        ? reconcileChecksum(sc, tc, schema, tableName, mc.targetSchema(), softDelete, sampleSize)
                        : reconcileCount(sc, tc, schema, tableName, mc.targetSchema(), softDelete);
                r.setRunId(run.getId());
                resultRepo.save(r);
                if ("MISMATCH".equals(r.getStatus())) mismatched++;
            }
            run.setStatus("COMPLETED");
        } catch (Exception e) {
            log.warn("Reconciliation run {} failed: {}", run.getId(), e.getMessage());
            run.setStatus("FAILED");
        }
        run.setMismatched(mismatched);
        run.setFinishedAt(OffsetDateTime.now());
        run = runRepo.save(run);

        return RunDto.from(run, resultRepo.findByRunIdOrderByTableName(run.getId()));
    }

    private ReconciliationResult reconcileCount(Connection sc, Connection tc, String schema, String table,
                                                String targetSchema, boolean softDelete) {
        ReconciliationResult r = newResult(schema, table);
        try {
            long source = count(sc, "SELECT COUNT(*) FROM [" + schema + "].[" + table + "]");
            String targetSql = "SELECT COUNT(*) FROM " + targetSchema + "." + ReconciliationLogic.snakeCase(table)
                    + (softDelete ? " WHERE __cdc_deleted IS NOT TRUE" : "");
            long target = count(tc, targetSql);
            var outcome = ReconciliationLogic.countOutcome(source, target);
            r.setSourceCount(source);
            r.setTargetCount(target);
            r.setDifference(outcome.difference());
            r.setStatus(outcome.status());
        } catch (Exception e) {
            r.setStatus("ERROR");
            r.setError(e.getMessage());
        }
        return r;
    }

    private ReconciliationResult reconcileChecksum(Connection sc, Connection tc, String schema, String table,
                                                   String targetSchema, boolean softDelete, int sampleSize) {
        ReconciliationResult r = newResult(schema, table);
        try {
            Optional<String> pkOpt = singlePrimaryKey(sc, schema, table);
            if (pkOpt.isEmpty()) {
                r.setStatus("SKIPPED");
                r.setError("Checksum sampling requires exactly one primary-key column");
                return r;
            }
            String pk = pkOpt.get();

            // Sample full source rows (keyed by normalised PK) so we can compare content too.
            Map<String, Map<String, Object>> srcRows = new LinkedHashMap<>();
            List<String> srcCols = new ArrayList<>();
            String srcSql = "SELECT TOP (" + Math.max(1, sampleSize) + ") * FROM ["
                    + schema + "].[" + table + "] ORDER BY [" + pk + "]";
            try (Statement st = sc.createStatement(); ResultSet rs = st.executeQuery(srcSql)) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) srcCols.add(md.getColumnLabel(i));
                while (rs.next()) {
                    String key = ReconciliationLogic.normalizeKey(rs.getObject(pk));
                    if (key == null) continue;
                    Map<String, Object> row = new HashMap<>();
                    for (String c : srcCols) row.put(c, rs.getObject(c));
                    srcRows.put(key, row);
                }
            }
            Set<String> sample = srcRows.keySet();

            // Fetch the matching target rows (PK cast to lower text to bridge the type conversion).
            String tgtPk = ReconciliationLogic.snakeCase(pk);
            Map<String, Map<String, Object>> tgtRows = new HashMap<>();
            Set<String> tgtCols = new HashSet<>();
            if (!sample.isEmpty()) {
                String tgtSql = "SELECT * FROM " + targetSchema + "." + ReconciliationLogic.snakeCase(table)
                        + " WHERE lower(cast(" + tgtPk + " AS text)) = ANY(?)"
                        + (softDelete ? " AND __cdc_deleted IS NOT TRUE" : "");
                try (PreparedStatement ps = tc.prepareStatement(tgtSql)) {
                    ps.setArray(1, tc.createArrayOf("text", sample.toArray()));
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) tgtCols.add(md.getColumnLabel(i).toLowerCase());
                        while (rs.next()) {
                            String key = ReconciliationLogic.normalizeKey(rs.getObject(tgtPk));
                            if (key == null) continue;
                            Map<String, Object> row = new HashMap<>();
                            for (String c : tgtCols) row.put(c, rs.getObject(c));
                            tgtRows.put(key, row);
                        }
                    }
                }
            }

            // Compare non-PK source columns whose snake_case form exists on the target.
            List<String> compareCols = srcCols.stream()
                    .filter(c -> !c.equalsIgnoreCase(pk))
                    .filter(c -> tgtCols.contains(ReconciliationLogic.snakeCase(c)))
                    .sorted()
                    .toList();

            long missing = 0, changed = 0;
            for (String key : sample) {
                Map<String, Object> tgt = tgtRows.get(key);
                if (tgt == null) { missing++; continue; }
                Map<String, Object> src = srcRows.get(key);
                String srcHash = ReconciliationLogic.rowChecksum(
                        compareCols.stream().map(c -> ReconciliationLogic.normalizeValue(src.get(c))).toList());
                String tgtHash = ReconciliationLogic.rowChecksum(
                        compareCols.stream()
                                .map(c -> ReconciliationLogic.normalizeValue(tgt.get(ReconciliationLogic.snakeCase(c))))
                                .toList());
                if (!srcHash.equals(tgtHash)) changed++;
            }

            r.setSampled((long) sample.size());
            r.setMissing(missing);
            r.setChanged(changed);
            r.setStatus((missing == 0 && changed == 0) ? "MATCH" : "MISMATCH");
        } catch (Exception e) {
            r.setStatus("ERROR");
            r.setError(e.getMessage());
        }
        return r;
    }

    private Optional<String> singlePrimaryKey(Connection sc, String schema, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        try (ResultSet rs = sc.getMetaData().getPrimaryKeys(sc.getCatalog(), schema, table)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
        }
        return pks.size() == 1 ? Optional.of(pks.get(0)) : Optional.empty();
    }

    private long count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private ReconciliationResult newResult(String schema, String table) {
        ReconciliationResult r = new ReconciliationResult();
        r.setSchemaName(schema);
        r.setTableName(table);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (v instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }

    private DbConnection requireConnection(UUID id, String role) {
        if (id == null) throw new IllegalArgumentException("Project has no " + role + " connection configured");
        return connections.findById(id)
                .orElseThrow(() -> new NotFoundException(role + " connection " + id + " not found"));
    }
}

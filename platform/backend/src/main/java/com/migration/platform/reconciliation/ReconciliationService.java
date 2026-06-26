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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-table source-vs-target row-count reconciliation (issue #47). Soft-delete aware: when the
 * project uses SOFT deletes, rows flagged {@code __cdc_deleted} are excluded from the target count.
 * Counts run synchronously; suitable for moderate table counts (async batching tracked in #49).
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

    @Transactional
    public RunDto run(UUID projectId) {
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
                ReconciliationResult r = reconcileOne(sc, tc, schema, tableName, mc.targetSchema(), softDelete);
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

    private ReconciliationResult reconcileOne(Connection sc, Connection tc, String schema, String table,
                                              String targetSchema, boolean softDelete) {
        ReconciliationResult r = new ReconciliationResult();
        r.setSchemaName(schema);
        r.setTableName(table);
        try {
            long source = count(sc, "SELECT COUNT(*) FROM [" + schema + "].[" + table + "]");
            String targetTable = snakeCase(table);
            String targetSql = "SELECT COUNT(*) FROM " + targetSchema + "." + targetTable
                    + (softDelete ? " WHERE __cdc_deleted IS NOT TRUE" : "");
            long target = count(tc, targetSql);
            r.setSourceCount(source);
            r.setTargetCount(target);
            r.setDifference(source - target);
            r.setStatus(source == target ? "MATCH" : "MISMATCH");
        } catch (Exception e) {
            r.setStatus("ERROR");
            r.setError(e.getMessage());
        }
        return r;
    }

    private long count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (v instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }

    /** Mirrors the SnakeCaseTransform SMT so target table names match. */
    private String snakeCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return result.toLowerCase();
    }

    private DbConnection requireConnection(UUID id, String role) {
        if (id == null) throw new IllegalArgumentException("Project has no " + role + " connection configured");
        return connections.findById(id)
                .orElseThrow(() -> new NotFoundException(role + " connection " + id + " not found"));
    }
}

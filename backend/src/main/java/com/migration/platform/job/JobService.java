package com.migration.platform.job;

import com.migration.platform.bulkload.BulkCopyService;
import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connect.KafkaConnectClient;
import com.migration.platform.connection.CdcReadinessService;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connector.ConnectorConfigService;
import com.migration.platform.job.dto.JobResponse;
import com.migration.platform.job.dto.TableStatusResponse;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.project.ProjectStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the migration-job lifecycle (issue #23). {@link #start} generates Debezium source/sink
 * connectors from the project config and deploys them via the Connect proxy; {@link #stop} removes
 * them. Delete semantics are resolved per the project's strategy (issue #25).
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repo;
    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final KafkaConnectClient connect;
    private final ConnectorConfigService configService;
    private final CryptoService crypto;
    private final TableStatusRepository tableStatus;
    private final com.migration.platform.audit.AuditService audit;
    private final com.migration.platform.connection.TargetSchemaService targetSchema;
    private final CdcReadinessService cdcReadiness;
    private final BulkCopyService bulkCopy;

    public JobService(JobRepository repo, ProjectRepository projects, ConnectionRepository connections,
                      KafkaConnectClient connect, ConnectorConfigService configService, CryptoService crypto,
                      TableStatusRepository tableStatus, com.migration.platform.audit.AuditService audit,
                      com.migration.platform.connection.TargetSchemaService targetSchema,
                      CdcReadinessService cdcReadiness, BulkCopyService bulkCopy) {
        this.repo = repo;
        this.projects = projects;
        this.connections = connections;
        this.connect = connect;
        this.configService = configService;
        this.crypto = crypto;
        this.tableStatus = tableStatus;
        this.audit = audit;
        this.targetSchema = targetSchema;
        this.cdcReadiness = cdcReadiness;
        this.bulkCopy = bulkCopy;
    }

    @Transactional(readOnly = true)
    public List<TableStatusResponse> tablesForJob(UUID jobId) {
        return tableStatus.findByJobIdOrderByTableName(jobId).stream().map(TableStatusResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<JobResponse> listForProject(UUID projectId) {
        return repo.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(JobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID id) {
        return JobResponse.from(find(id));
    }

    @Transactional
    public JobResponse create(UUID projectId) {
        if (!projects.existsById(projectId)) {
            throw new NotFoundException("Project " + projectId + " not found");
        }
        MigrationJob job = new MigrationJob();
        job.setProjectId(projectId);
        job.setStatus(JobStatus.CREATED);
        return JobResponse.from(repo.save(job));
    }

    /** Generate connector configs without deploying — for the UI preview / debugging. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID projectId) {
        MigrationProject project = requireProject(projectId);
        DbConnection src = requireConnection(project.getSourceConnectionId(), "source");
        DbConnection tgt = requireConnection(project.getTargetConnectionId(), "target");
        String maskedSrc = "******";
        String maskedTgt = "******";
        return Map.of(
                "source", configService.sourceConnector(project, src, maskedSrc),
                "sink", configService.sinkConnector(project, tgt, maskedTgt, src.getDbType()));
    }

    @Transactional
    public JobResponse start(UUID id) {
        MigrationJob job = find(id);
        JobTransitions.assertAllowed(JobTransitions.Action.START, job.getStatus());
        MigrationProject project = requireProject(job.getProjectId());
        try {
            DbConnection src = requireConnection(project.getSourceConnectionId(), "source");
            DbConnection tgt = requireConnection(project.getTargetConnectionId(), "target");

            // The Debezium source connector refuses to start — even for the full-load snapshot — unless
            // the source is CDC-ready (database-level CDC AND a capture instance per selected table).
            // Rather than block, auto-fall back to a one-time CDC-free full load (bulk copy) so a
            // migration still completes when CDC can't be enabled on the source (#191).
            List<String> tables = selectedTables(project);
            boolean cdcReady = cdcReadiness.check(project.getSourceConnectionId()).ready()
                    && cdcReadiness.tablesMissingCdc(project.getSourceConnectionId(), tables).isEmpty();
            if (!cdcReady) {
                log.info("Job {}: source not CDC-ready — running a CDC-free full load (bulk copy)", id);
                return startBulkFullLoad(job, project, tables);
            }

            Map<String, Object> source = configService.sourceConnector(project, src, crypto.decrypt(src.getPasswordEnc()));
            Map<String, Object> sink = configService.sinkConnector(project, tgt, crypto.decrypt(tgt.getPasswordEnc()), src.getDbType());

            // The JDBC sink creates target tables but not the schema — ensure it exists first so the
            // sink doesn't fail silently when the configured target schema is missing (#).
            String tgtSchema = com.migration.platform.connector.MigrationConfig
                    .from(project.getConfig(), project.getName()).targetSchema();
            targetSchema.ensure(tgt, tgtSchema);

            connect.createConnector(source);
            connect.createConnector(sink);

            // Re-deliver to the target on every (re)start so a changed target schema / naming strategy is
            // fully repopulated, instead of being silently skipped because the sink's Kafka consumer group
            // resumed past already-consumed records. Idempotent — the JDBC sink upserts by primary key.
            resetSinkOffsets(configService.sinkName(project));

            job.setSourceConnectorName(configService.sourceName(project));
            job.setSinkConnectorName(configService.sinkName(project));
            job.setStatus(JobStatus.SNAPSHOT);
            job.setPhase("snapshot");
            job.setStartedAt(OffsetDateTime.now());
            job.setError(null);

            project.setStatus(ProjectStatus.ACTIVE);
            projects.save(project);
            audit.record("JOB_START", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));

            seedTableStatus(job, project);
        } catch (IllegalArgumentException e) {
            throw e; // surfaced as 400 (e.g. missing connections)
        } catch (Exception e) {
            // Persist FAILED rather than rolling back so the UI can show why.
            log.warn("Failed to start job {}: {}", id, e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setError(e.getMessage());
        }
        return JobResponse.from(repo.save(job));
    }

    @Transactional
    public JobResponse pause(UUID id) {
        MigrationJob job = find(id);
        JobTransitions.assertAllowed(JobTransitions.Action.PAUSE, job.getStatus());
        forEachConnector(job, connect::pause);
        job.setStatus(JobStatus.PAUSED);
        audit.record("JOB_PAUSE", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));
        return JobResponse.from(repo.save(job));
    }

    @Transactional
    public JobResponse resume(UUID id) {
        MigrationJob job = find(id);
        JobTransitions.assertAllowed(JobTransitions.Action.RESUME, job.getStatus());
        forEachConnector(job, connect::resume);
        job.setStatus(JobStatus.RUNNING);
        audit.record("JOB_RESUME", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));
        return JobResponse.from(repo.save(job));
    }

    @Transactional
    public JobResponse stop(UUID id) {
        MigrationJob job = find(id);
        JobTransitions.assertAllowed(JobTransitions.Action.STOP, job.getStatus());
        forEachConnector(job, name -> {
            try { connect.delete(name); }
            catch (Exception e) { log.warn("Could not delete connector {}: {}", name, e.getMessage()); }
        });
        job.setStatus(JobStatus.STOPPED);
        job.setFinishedAt(OffsetDateTime.now());
        audit.record("JOB_STOP", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));
        return JobResponse.from(repo.save(job));
    }

    /**
     * Re-run a clean full load (#131): reset the source connector's committed offsets so Debezium
     * re-runs its initial snapshot, then resume. Restarting normally only resumes from offsets and
     * never re-snapshots — this is the explicit, guarded way to recapture everything. The sink keeps
     * running and re-applies the re-emitted rows (upsert), refreshing the target.
     */
    @Transactional
    public JobResponse reloadFull(UUID id) {
        return reloadFull(id, false);
    }

    /**
     * Re-run the full load. When {@code cleanTarget} is true, the project's target tables are first
     * truncated so the re-snapshot reconciles the target exactly to the source — removing rows that
     * no longer exist on the source, which an upsert-only reload cannot (#163).
     */
    public JobResponse reloadFull(UUID id, boolean cleanTarget) {
        MigrationJob job = find(id);
        if (job.getSourceConnectorName() == null
                || !EnumSet.of(JobStatus.RUNNING, JobStatus.SNAPSHOT, JobStatus.PAUSED).contains(job.getStatus())) {
            throw new IllegalArgumentException(
                    "Re-run full load needs an active run (running, snapshotting or paused) with deployed connectors");
        }
        MigrationProject project = requireProject(job.getProjectId());
        String source = job.getSourceConnectorName();

        if (cleanTarget) {
            // Truncate the target tables before re-snapshot so deleted/phantom rows don't survive.
            DbConnection tgt = requireConnection(project.getTargetConnectionId(), "target");
            var mc = com.migration.platform.connector.MigrationConfig.from(project.getConfig(), project.getName());
            List<String> targetTables = selectedTables(project).stream()
                    .map(fq -> { String[] p = fq.split("\\.", 2); return p.length == 2 ? p[1] : p[0]; })
                    .map(name -> com.migration.platform.connector.TargetNaming.apply(name, mc.namingStrategy()))
                    .toList();
            int cleaned = targetSchema.truncateTables(tgt, mc.targetSchema(), targetTables);
            audit.record("JOB_CLEAN_TARGET", job.getProjectId().toString(),
                    java.util.Map.of("jobId", id.toString(), "tables", cleaned));
        }
        try {
            connect.stop(source);          // → STOPPED (required before clearing offsets)
            resetOffsetsWithRetry(source); // clear committed offsets (Connect 3.6+)
            connect.resume(source);        // resume → re-snapshot from scratch
            // Also rewind the sink so the re-snapshot lands fully in the (possibly changed) target.
            if (job.getSinkConnectorName() != null) resetSinkOffsets(job.getSinkConnectorName());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not reset source offsets; Kafka Connect must support the offsets API (3.6+): "
                            + e.getMessage(), e);
        }
        job.setStatus(JobStatus.SNAPSHOT);
        job.setPhase("snapshot");
        job.setStartedAt(OffsetDateTime.now());
        job.setError(null);
        seedTableStatus(job, project);
        audit.record("JOB_RELOAD", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));
        return JobResponse.from(repo.save(job));
    }

    /** Deleting offsets requires the connector to have reached STOPPED, which the herder applies async. */
    private void resetOffsetsWithRetry(String connector) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try { connect.deleteOffsets(connector); return; }
            catch (RuntimeException e) { last = e; Thread.sleep(500); }
        }
        if (last != null) throw last;
    }

    /**
     * Best-effort reset of a sink connector's consumer-group offsets (stop → clear → resume) so it
     * re-reads its topics from the start. Ensures a changed target schema / naming strategy is fully
     * re-delivered rather than skipped. Non-fatal: a reset failure must not block the run.
     */
    private void resetSinkOffsets(String sinkConnector) {
        try {
            connect.stop(sinkConnector);
            resetOffsetsWithRetry(sinkConnector);
            connect.resume(sinkConnector);
        } catch (Exception e) {
            log.warn("Could not reset sink offsets for {} (continuing): {}", sinkConnector, e.getMessage());
        }
    }

    /** (Re)seed per-table status rows to PENDING for a fresh run (#19, #129). */
    private void seedTableStatus(MigrationJob job, MigrationProject project) {
        String snapshotMode = com.migration.platform.connector.MigrationConfig
                .from(project.getConfig(), project.getName()).snapshotMode();
        boolean snapshotsData = !java.util.Set.of("schema_only", "no_data").contains(snapshotMode);
        tableStatus.deleteByJobId(job.getId());
        for (String fq : selectedTables(project)) {
            String[] parts = fq.split("\\.", 2);
            TableStatus ts = new TableStatus();
            ts.setJobId(job.getId());
            ts.setSchemaName(parts.length == 2 ? parts[0] : "dbo");
            ts.setTableName(parts.length == 2 ? parts[1] : parts[0]);
            ts.setPhase(snapshotsData ? "DATA" : "CDC");
            ts.setStatus("PENDING");
            tableStatus.save(ts);
        }
    }

    private void forEachConnector(MigrationJob job, java.util.function.Consumer<String> op) {
        if (job.getSourceConnectorName() != null) op.accept(job.getSourceConnectorName());
        if (job.getSinkConnectorName() != null) op.accept(job.getSinkConnectorName());
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (v instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }

    /**
     * Block job start when the source database isn't CDC-ready, with the readiness findings surfaced
     * as a 400 instead of letting the Debezium connector fail cryptically at deploy time (#191).
     */
    /**
     * CDC-free full load (#191): when the source isn't CDC-ready, run a one-time JDBC bulk copy instead
     * of deploying Debezium connectors. The job moves to SNAPSHOT/"full-load" and the actual copy runs
     * on a background thread; it's kicked off only after this transaction commits, so the worker reads
     * a persisted job. No connectors are created, so there's no ongoing streaming — this is a one-shot
     * migration of the current data.
     */
    private JobResponse startBulkFullLoad(MigrationJob job, MigrationProject project, List<String> tables) {
        var mc = com.migration.platform.connector.MigrationConfig.from(project.getConfig(), project.getName());
        job.setSourceConnectorName(null);
        job.setSinkConnectorName(null);
        job.setStatus(JobStatus.SNAPSHOT);
        job.setPhase("full-load");
        job.setStartedAt(OffsetDateTime.now());
        job.setError(null);
        project.setStatus(ProjectStatus.ACTIVE);
        projects.save(project);
        seedTableStatus(job, project);
        MigrationJob saved = repo.save(job);
        audit.record("JOB_START", job.getProjectId().toString(),
                java.util.Map.of("jobId", saved.getId().toString(), "mode", "full-load"));

        var req = new BulkCopyService.BulkCopyRequest(
                saved.getId(), project.getId(), project.getSourceConnectionId(), project.getTargetConnectionId(),
                mc.targetSchema(), mc.namingStrategy(), tables, mc.snapshotFetchSize());
        // Start the copy only after the SNAPSHOT state is committed, so the worker thread doesn't race
        // the transaction and read a stale (CREATED) job.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { bulkCopy.startAsync(req); }
                    });
        } else {
            bulkCopy.startAsync(req);
        }
        return JobResponse.from(saved);
    }

    private MigrationProject requireProject(UUID id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }

    private DbConnection requireConnection(UUID id, String role) {
        if (id == null) {
            throw new IllegalArgumentException("Project has no " + role + " connection configured");
        }
        return connections.findById(id)
                .orElseThrow(() -> new NotFoundException(role + " connection " + id + " not found"));
    }

    private MigrationJob find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Job " + id + " not found"));
    }
}

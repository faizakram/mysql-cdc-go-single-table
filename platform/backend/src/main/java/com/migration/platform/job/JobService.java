package com.migration.platform.job;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connect.KafkaConnectClient;
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

    public JobService(JobRepository repo, ProjectRepository projects, ConnectionRepository connections,
                      KafkaConnectClient connect, ConnectorConfigService configService, CryptoService crypto,
                      TableStatusRepository tableStatus, com.migration.platform.audit.AuditService audit) {
        this.repo = repo;
        this.projects = projects;
        this.connections = connections;
        this.connect = connect;
        this.configService = configService;
        this.crypto = crypto;
        this.tableStatus = tableStatus;
        this.audit = audit;
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

            Map<String, Object> source = configService.sourceConnector(project, src, crypto.decrypt(src.getPasswordEnc()));
            Map<String, Object> sink = configService.sinkConnector(project, tgt, crypto.decrypt(tgt.getPasswordEnc()), src.getDbType());

            connect.createConnector(source);
            connect.createConnector(sink);

            job.setSourceConnectorName(configService.sourceName(project));
            job.setSinkConnectorName(configService.sinkName(project));
            job.setStatus(JobStatus.SNAPSHOT);
            job.setPhase("snapshot");
            job.setStartedAt(OffsetDateTime.now());
            job.setError(null);

            project.setStatus(ProjectStatus.ACTIVE);
            projects.save(project);
            audit.record("JOB_START", job.getProjectId().toString(), java.util.Map.of("jobId", id.toString()));

            // Persist per-table status for this run in the metadata store (replaces JSON, #19).
            // Rows start PENDING; ProgressTracker advances rowsSynced/phase/status as the run proceeds (#129).
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

package com.migration.platform.job;

import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.connect.KafkaConnectClient;
import com.migration.platform.monitoring.LagService;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Keeps per-table migration progress live (#19, #129). Earlier, {@code table_status} rows were
 * written once on job start with a hardcoded phase/status and {@code rowsSynced} never advanced, so
 * the Jobs drawer showed no real per-table progress.
 *
 * <p>This scheduled tracker, for every active job, reads the cumulative records produced per table
 * from Kafka (the topic end offset — one record per row during snapshot) and updates each table's
 * {@code rowsSynced}, {@code phase} and {@code status}. It also detects snapshot completion from the
 * source connector's committed offsets and advances the job {@code SNAPSHOT → RUNNING}.
 */
@Component
public class ProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);
    private static final List<JobStatus> ACTIVE = List.of(JobStatus.SNAPSHOT, JobStatus.RUNNING);

    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final TableStatusRepository tableStatus;
    private final KafkaConnectClient connect;
    private final LagService lag;

    public ProgressTracker(JobRepository jobs, ProjectRepository projects, TableStatusRepository tableStatus,
                           KafkaConnectClient connect, LagService lag) {
        this.jobs = jobs;
        this.projects = projects;
        this.tableStatus = tableStatus;
        this.connect = connect;
        this.lag = lag;
    }

    @Scheduled(fixedDelayString = "${platform.progress.interval-ms:15000}", initialDelay = 20000)
    @Transactional
    public void refresh() {
        List<MigrationJob> active = jobs.findByStatusIn(ACTIVE);
        for (MigrationJob job : active) {
            try {
                track(job);
            } catch (Exception e) {
                log.debug("Progress refresh failed for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    private void track(MigrationJob job) {
        List<TableStatus> rows = tableStatus.findByJobIdOrderByTableName(job.getId());
        if (rows.isEmpty()) return;

        MigrationProject project = projects.findById(job.getProjectId()).orElse(null);
        if (project == null) return;
        String prefix = MigrationConfig.from(project.getConfig(), project.getName()).topicPrefix();

        Map<String, Long> recordsByTable = lag.recordsByTable(prefix);
        String failure = sourceTaskFailure(job.getSourceConnectorName());
        Boolean snapshotDone = snapshotComplete(job.getSourceConnectorName());

        // Advance the job out of SNAPSHOT once Debezium reports the snapshot finished.
        boolean streaming = job.getStatus() == JobStatus.RUNNING || Boolean.TRUE.equals(snapshotDone);
        if (job.getStatus() == JobStatus.SNAPSHOT && Boolean.TRUE.equals(snapshotDone) && failure == null) {
            job.setStatus(JobStatus.RUNNING);
            job.setPhase("cdc");
            jobs.save(job);
            log.info("Job {} snapshot complete → RUNNING (CDC)", job.getId());
        }

        for (TableStatus ts : rows) {
            Long produced = lookup(recordsByTable, ts.getTableName());
            if (produced != null) ts.setRowsSynced(produced);

            if (failure != null) {
                ts.setStatus("FAILED");
                ts.setError(failure.length() > 2000 ? failure.substring(0, 2000) : failure);
            } else if (streaming) {
                ts.setPhase("CDC");
                ts.setStatus("IN_PROGRESS");
                ts.setError(null);
            } else { // still snapshotting the initial data
                ts.setPhase("DATA");
                ts.setStatus(produced != null && produced > 0 ? "IN_PROGRESS" : "PENDING");
                ts.setError(null);
            }
            tableStatus.save(ts); // @PreUpdate stamps updatedAt
        }
    }

    /** First FAILED source task's trace (or generic message), else null when the source is healthy. */
    private String sourceTaskFailure(String sourceConnector) {
        if (sourceConnector == null) return null;
        try {
            return failedTaskTrace(connect.connectorStatus(sourceConnector));
        } catch (Exception e) {
            log.debug("Could not read status for {}: {}", sourceConnector, e.getMessage());
            return null;
        }
    }

    private Boolean snapshotComplete(String sourceConnector) {
        if (sourceConnector == null) return null;
        return parseSnapshotComplete(connect.connectorOffsets(sourceConnector));
    }

    // ---- pure helpers (unit-tested in ProgressTrackerTest) ----

    /** Case-insensitive table lookup (topic segment vs. configured table name may differ in case). */
    static Long lookup(Map<String, Long> byTable, String table) {
        Long exact = byTable.get(table);
        if (exact != null) return exact;
        return byTable.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(table))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** First FAILED task's trace from a Connect {@code /status} body, else null. */
    static String failedTaskTrace(Map<String, Object> status) {
        if (status == null) return null;
        if (status.get("tasks") instanceof List<?> tasks) {
            for (Object t : tasks) {
                if (t instanceof Map<?, ?> task && "FAILED".equals(task.get("state"))) {
                    Object trace = task.get("trace");
                    return trace != null ? trace.toString() : "Source task failed";
                }
            }
        }
        return null;
    }

    /**
     * Whether the source connector has finished its snapshot, read from a Connect {@code /offsets}
     * body: during snapshot Debezium tags each offset with a truthy {@code snapshot} flag. Returns
     * null when offsets are unavailable (older Connect / not yet committed) so the caller stays put.
     */
    static Boolean parseSnapshotComplete(Map<String, Object> body) {
        if (body == null) return null;
        if (!(body.get("offsets") instanceof List<?> offsets) || offsets.isEmpty()) return null;
        for (Object entry : offsets) {
            if (entry instanceof Map<?, ?> e && e.get("offset") instanceof Map<?, ?> off) {
                if (isTruthy(off.get("snapshot"))) return false;
            }
        }
        return true;
    }

    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString();
        return "true".equalsIgnoreCase(s) || "incremental".equalsIgnoreCase(s) || "true,first".equalsIgnoreCase(s);
    }
}

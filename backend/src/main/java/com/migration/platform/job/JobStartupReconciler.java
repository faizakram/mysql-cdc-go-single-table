package com.migration.platform.job;

import com.migration.platform.audit.AuditService;
import com.migration.platform.connect.KafkaConnectClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On startup, reconcile tracked jobs against the live Kafka Connect connectors (#175).
 *
 * <p>A job is persisted as RUNNING/SNAPSHOT/PAUSED, but its connectors live in Kafka Connect. If the
 * backend (or Kafka) restarted and the connectors are gone — e.g. Connect's config topic wasn't
 * durable — the job would otherwise keep showing "RUNNING" while no CDC is actually flowing. This
 * detects that drift and marks such jobs FAILED with a clear message so the UI reflects reality and
 * the operator can re-run. Runs off the boot thread; if Connect is simply unreachable at startup it
 * skips (it doesn't fail jobs just because Connect is slow to come up).
 */
@Component
public class JobStartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(JobStartupReconciler.class);
    private static final EnumSet<JobStatus> ACTIVE = EnumSet.of(JobStatus.RUNNING, JobStatus.SNAPSHOT, JobStatus.PAUSED);

    private final JobRepository repo;
    private final KafkaConnectClient connect;
    private final AuditService audit;

    public JobStartupReconciler(JobRepository repo, KafkaConnectClient connect, AuditService audit) {
        this.repo = repo;
        this.connect = connect;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // Off the boot thread so a slow/unreachable Connect never delays startup.
        Thread t = new Thread(this::reconcile, "job-startup-reconciler");
        t.setDaemon(true);
        t.start();
    }

    void reconcile() {
        List<MigrationJob> active = repo.findByStatusIn(ACTIVE);
        if (active.isEmpty()) return;

        Set<String> live;
        try {
            live = new HashSet<>(connect.listConnectors());
        } catch (Exception e) {
            log.warn("Skipping startup job reconciliation — Kafka Connect not reachable yet: {}", e.getMessage());
            return;
        }

        int orphaned = 0;
        for (MigrationJob job : active) {
            String src = job.getSourceConnectorName();
            String sink = job.getSinkConnectorName();
            boolean missing = (src != null && !live.contains(src)) || (sink != null && !live.contains(sink));
            if (missing) {
                job.setStatus(JobStatus.FAILED);
                job.setError("Connectors not found in Kafka Connect after restart (the data plane lost them). "
                        + "Re-run the full load to redeploy.");
                repo.save(job);   // own transaction per save
                audit.record("JOB_ORPHANED", job.getProjectId().toString(),
                        Map.of("jobId", job.getId().toString(), "source", String.valueOf(src), "sink", String.valueOf(sink)));
                orphaned++;
            }
        }
        if (orphaned > 0) {
            log.warn("Startup reconciliation marked {} of {} active job(s) FAILED — connectors missing from Connect",
                    orphaned, active.size());
        } else {
            log.info("Startup reconciliation: {} active job(s), all connectors present", active.size());
        }
    }
}

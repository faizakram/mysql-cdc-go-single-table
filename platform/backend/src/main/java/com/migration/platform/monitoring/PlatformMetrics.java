package com.migration.platform.monitoring;

import com.migration.platform.alert.AlertService;
import com.migration.platform.job.JobRepository;
import com.migration.platform.job.JobStatus;
import com.migration.platform.monitoring.dto.ConnectorHealth;
import com.migration.platform.monitoring.dto.ProjectHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom business metrics exported via Micrometer → Prometheus at {@code /actuator/prometheus} (#50).
 *
 * <p>Two kinds:
 * <ul>
 *   <li><b>Static gauges</b> evaluated at scrape time from cheap local DB queries — job counts by
 *       status and the number of firing alerts.</li>
 *   <li><b>Per-project gauges</b> (lag, connector up/down) maintained as {@link MultiGauge}s and
 *       refreshed on a schedule, since they require a call out to Kafka Connect.</li>
 * </ul>
 * Grafana dashboards (#51) and alert rules build on these series.
 */
@Component
public class PlatformMetrics {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetrics.class);
    private static final List<JobStatus> ACTIVE = List.of(JobStatus.RUNNING, JobStatus.SNAPSHOT);

    private final MonitoringService monitoring;
    private final MultiGauge sinkLag;
    private final MultiGauge connectorUp;

    public PlatformMetrics(MeterRegistry registry, JobRepository jobs, AlertService alerts,
                           MonitoringService monitoring) {
        this.monitoring = monitoring;

        // Active jobs (running + snapshotting).
        Gauge.builder("migration_active_jobs", () -> jobs.countByStatusIn(ACTIVE))
                .description("Number of migration jobs currently running or snapshotting")
                .register(registry);

        // One series per job lifecycle state, tagged by status — drives job-state panels.
        for (JobStatus s : JobStatus.values()) {
            Gauge.builder("migration_jobs", () -> jobs.countByStatusIn(List.of(s)))
                    .description("Migration jobs by status")
                    .tag("status", s.name())
                    .register(registry);
        }

        // Open (firing) alerts — should normally be zero.
        Gauge.builder("migration_alerts_firing", alerts, AlertService::firingCount)
                .description("Number of alerts currently firing")
                .register(registry);

        this.sinkLag = MultiGauge.builder("migration_sink_lag_records")
                .description("Sink consumer-group lag in records, per project")
                .register(registry);
        this.connectorUp = MultiGauge.builder("migration_connector_up")
                .description("Connector RUNNING (1) or not (0), per project/connector/role")
                .register(registry);
    }

    /**
     * Refresh per-project gauges from live connector health. Interval is configurable; defaults to
     * 15s, fast enough for dashboards without hammering Connect.
     */
    @Scheduled(fixedDelayString = "${platform.metrics.refresh-ms:15000}",
            initialDelayString = "${platform.metrics.refresh-ms:15000}")
    public void refresh() {
        try {
            List<ProjectHealth> overview = monitoring.overview();

            List<MultiGauge.Row<?>> lagRows = new ArrayList<>();
            List<MultiGauge.Row<?>> upRows = new ArrayList<>();
            for (ProjectHealth p : overview) {
                if (p.lagRecords() != null) {
                    lagRows.add(MultiGauge.Row.of(Tags.of("project", p.projectName()), p.lagRecords()));
                }
                for (ConnectorHealth c : p.connectors()) {
                    upRows.add(MultiGauge.Row.of(
                            Tags.of("project", p.projectName(), "connector", c.name(), "role", c.role()),
                            "RUNNING".equals(c.state()) ? 1 : 0));
                }
            }
            sinkLag.register(lagRows, true);
            connectorUp.register(upRows, true);
        } catch (Exception e) {
            log.debug("Metrics refresh skipped: {}", e.getMessage());
        }
    }
}

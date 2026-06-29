package com.migration.platform.advisor;

import com.migration.platform.common.NotFoundException;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.monitoring.LiveStreamMonitor;
import com.migration.platform.monitoring.MonitoringService;
import com.migration.platform.monitoring.dto.ProjectHealth;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Gathers a project's live performance signals — current connector tuning ({@link MigrationConfig}),
 * observed throughput + per-table lag ({@link LiveStreamMonitor}), and sink consumer-group lag + job
 * status ({@link MonitoringService}) — and runs them through {@link PerformanceAdvisor} (#217).
 */
@Service
public class PerformanceAdvisorService {

    private final ProjectRepository projects;
    private final MonitoringService monitoring;
    private final LiveStreamMonitor liveMonitor;

    public PerformanceAdvisorService(ProjectRepository projects, MonitoringService monitoring,
                                     LiveStreamMonitor liveMonitor) {
        this.projects = projects;
        this.monitoring = monitoring;
        this.liveMonitor = liveMonitor;
    }

    @Transactional(readOnly = true)
    public AdvisorReport advise(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());

        // Observed throughput + worst per-table lag from the live CDC monitor.
        double eventsPerSec = 0;
        long maxLagMs = 0;
        for (var t : liveMonitor.snapshot(projectId.toString())) {
            eventsPerSec += t.eventsPerSec();
            maxLagMs = Math.max(maxLagMs, t.lastLagMs());
        }

        // Job status + sink consumer-group lag (records) from the monitoring overview.
        ProjectHealth health = monitoring.projectStatus(projectId);
        String jobStatus = health.jobStatus();
        Long sinkLag = health.lagRecords();

        PerformanceAdvisor.Signals signals = new PerformanceAdvisor.Signals(
                jobStatus, selectedTableCount(p),
                mc.snapshotMaxThreads(), mc.snapshotFetchSize(), mc.sinkBatchSize(),
                eventsPerSec, maxLagMs, sinkLag);

        return new AdvisorReport(projectId, jobStatus, Math.round(eventsPerSec), maxLagMs, sinkLag,
                PerformanceAdvisor.advise(signals));
    }

    private int selectedTableCount(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.size();
        if (v instanceof String s && !s.isBlank()) return s.split("\\s*,\\s*").length;
        return 0;
    }
}

package com.migration.platform.reconciliation;

import com.migration.platform.alert.AlertService;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.project.ProjectStatus;
import com.migration.platform.reconciliation.dto.ReconciliationDtos.RunDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic drift-detection reconciliation (#49). Runs validation for ACTIVE projects that opted in and
 * logs sustained drift (seeds alerting #52). Two opt-in levels:
 * <ul>
 *   <li>{@code config.onlineValidation = true} → CHECKSUM mode: samples rows and compares column content
 *       during CDC, catching value drift that row counts miss (online in-flight validation, #217).</li>
 *   <li>{@code config.autoReconcile = true} → COUNT mode: row-count drift only.</li>
 * </ul>
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ProjectRepository projects;
    private final ReconciliationService reconciliation;
    private final AlertService alerts;

    public ReconciliationScheduler(ProjectRepository projects, ReconciliationService reconciliation,
                                   AlertService alerts) {
        this.projects = projects;
        this.reconciliation = reconciliation;
        this.alerts = alerts;
    }

    @Scheduled(cron = "${platform.reconciliation.cron}")
    public void runScheduledReconciliations() {
        // Only ACTIVE projects can have drift worth reconciling — query them directly instead of
        // scanning every project (#214).
        for (MigrationProject p : projects.findByStatus(ProjectStatus.ACTIVE)) {
            boolean online = flag(p, "onlineValidation");
            if (!online && !flag(p, "autoReconcile")) continue;
            String mode = online ? "CHECKSUM" : "COUNT";   // online = content comparison during CDC (#217)
            try {
                RunDto run = reconciliation.run(p.getId(), mode, 1000);
                String dedup = "drift:" + p.getId();
                if (run.mismatched() > 0) {
                    String kind = online ? "content/row drift" : "row-count drift";
                    log.warn("{} detected for project '{}' ({}): {}/{} tables mismatched ({} mode)",
                            kind, p.getName(), p.getId(), run.mismatched(), run.totalTables(), mode);
                    alerts.raise(dedup, "WARNING", "DRIFT",
                            kind + " in project '" + p.getName() + "': " + run.mismatched() + "/"
                                    + run.totalTables() + " tables mismatched", p.getId());
                } else {
                    log.info("Scheduled {} reconciliation clean for project '{}' ({})", mode, p.getName(), p.getId());
                    alerts.resolve(dedup);
                }
            } catch (Exception e) {
                log.warn("Scheduled reconciliation failed for project {}: {}", p.getId(), e.getMessage());
            }
        }
    }

    private boolean flag(MigrationProject p, String key) {
        Object v = p.getConfig() == null ? null : p.getConfig().get(key);
        return (v instanceof Boolean b && b) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}

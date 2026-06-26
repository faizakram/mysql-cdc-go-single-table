package com.migration.platform.reconciliation;

import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.project.ProjectStatus;
import com.migration.platform.reconciliation.dto.ReconciliationDtos.RunDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic drift-detection reconciliation (#49). Runs row-count validation for ACTIVE projects that
 * opted in (config.autoReconcile = true) and logs sustained drift (seeds alerting #52).
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ProjectRepository projects;
    private final ReconciliationService reconciliation;

    public ReconciliationScheduler(ProjectRepository projects, ReconciliationService reconciliation) {
        this.projects = projects;
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${platform.reconciliation.cron}")
    public void runScheduledReconciliations() {
        for (MigrationProject p : projects.findAll()) {
            if (p.getStatus() != ProjectStatus.ACTIVE || !optedIn(p)) continue;
            try {
                RunDto run = reconciliation.run(p.getId(), "COUNT", 1000);
                if (run.mismatched() > 0) {
                    log.warn("Drift detected for project '{}' ({}): {}/{} tables mismatched",
                            p.getName(), p.getId(), run.mismatched(), run.totalTables());
                } else {
                    log.info("Scheduled reconciliation clean for project '{}' ({})", p.getName(), p.getId());
                }
            } catch (Exception e) {
                log.warn("Scheduled reconciliation failed for project {}: {}", p.getId(), e.getMessage());
            }
        }
    }

    private boolean optedIn(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("autoReconcile");
        return (v instanceof Boolean b && b) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}

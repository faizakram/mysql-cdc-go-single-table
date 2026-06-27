package com.migration.platform.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fires due schedules (#53). Runs every {@code platform.scheduler.sweep-cron}
 * (default: every 30s). The actual enqueue + concurrency control happens in
 * {@link ScheduleService#fireDue()} → {@link JobOrchestrator}.
 */
@Component
public class SchedulerSweeper {

    private static final Logger log = LoggerFactory.getLogger(SchedulerSweeper.class);

    private final ScheduleService schedules;

    public SchedulerSweeper(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(cron = "${platform.scheduler.sweep-cron:*/30 * * * * *}")
    public void sweep() {
        try {
            int fired = schedules.fireDue();
            if (fired > 0) log.info("Scheduler fired {} due schedule(s)", fired);
        } catch (Exception e) {
            log.warn("Scheduler sweep failed: {}", e.getMessage());
        }
    }
}

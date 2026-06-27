package com.migration.platform.dryrun;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Migration simulation (dry-run) + checkpoint/rollback (#104). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DryRunController {

    private final DryRunService dryRun;
    private final RecoveryService recovery;

    public DryRunController(DryRunService dryRun, RecoveryService recovery) {
        this.dryRun = dryRun;
        this.recovery = recovery;
    }

    /** Validate the project end-to-end without deploying connectors or writing to the target (#105). */
    @PostMapping("/dry-run")
    public DryRunService.DryRunReport dryRun(@PathVariable UUID projectId) {
        return dryRun.run(projectId);
    }

    /** Record target row counts as a checkpoint (#106). */
    @PostMapping("/checkpoint")
    public RecoveryService.Checkpoint checkpoint(@PathVariable UUID projectId) {
        return recovery.checkpoint(projectId);
    }

    /** DESTRUCTIVE: truncate the project's target tables to roll back to the pre-migration state (#106). */
    @PostMapping("/rollback")
    public RecoveryService.RollbackResult rollback(@PathVariable UUID projectId) {
        return recovery.rollback(projectId);
    }
}

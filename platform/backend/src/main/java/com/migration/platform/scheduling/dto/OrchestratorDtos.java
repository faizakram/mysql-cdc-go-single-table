package com.migration.platform.scheduling.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read models for the job queue / orchestrator (#54). */
public final class OrchestratorDtos {

    private OrchestratorDtos() {}

    /** A task that is running or waiting in the queue. */
    public record TaskView(
            UUID taskId,
            UUID projectId,
            String projectName,
            String kind,        // FULL_LOAD | VALIDATION
            String source,      // SCHEDULED | MANUAL
            String state,       // QUEUED | RUNNING
            OffsetDateTime enqueuedAt,
            OffsetDateTime startedAt
    ) {}

    /** Snapshot of the orchestrator: limits, what's running, and what's waiting. */
    public record OrchestratorStatus(
            int maxConcurrent,
            int running,
            int queued,
            List<TaskView> runningTasks,
            List<TaskView> queuedTasks
    ) {}
}

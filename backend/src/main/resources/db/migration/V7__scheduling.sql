-- Scheduling & job orchestration (epic #13).
-- Persisted per-project schedules for full-load and validation runs (#53). The job queue /
-- concurrency control (#54) is in-process and observed via the API, so no table is needed for it.
CREATE TABLE job_schedule (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES migration_project(id) ON DELETE CASCADE,
    kind          VARCHAR(20)  NOT NULL,            -- FULL_LOAD | VALIDATION
    cron          VARCHAR(120) NOT NULL,            -- Spring 6-field cron (sec min hour dom mon dow)
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at   TIMESTAMPTZ,
    last_status   VARCHAR(20),                      -- SUCCESS | FAILED | RUNNING
    next_run_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_schedule_project ON job_schedule(project_id);
CREATE INDEX idx_job_schedule_due ON job_schedule(enabled, next_run_at);

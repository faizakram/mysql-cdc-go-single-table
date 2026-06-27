-- Async, job-based integrity validation (#150). The integrity report (#96) used to run
-- synchronously inside the HTTP request; for multi-million-row tables that timed out and showed
-- nothing until complete. It now runs as a background job that persists a run plus per-table
-- results incrementally, so the UI can poll and render progress in real time.

CREATE TABLE validation_run (
    id               UUID PRIMARY KEY,
    project_id       UUID NOT NULL REFERENCES migration_project(id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL,        -- PENDING | RUNNING | COMPLETED | FAILED
    total_tables     INTEGER NOT NULL DEFAULT 0,
    completed_tables INTEGER NOT NULL DEFAULT 0,
    passed           INTEGER NOT NULL DEFAULT 0,
    failed           INTEGER NOT NULL DEFAULT 0,
    error            TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX idx_validation_run_project ON validation_run(project_id);

CREATE TABLE validation_result (
    id               UUID PRIMARY KEY,
    run_id           UUID NOT NULL REFERENCES validation_run(id) ON DELETE CASCADE,
    schema_name      VARCHAR(255) NOT NULL,
    table_name       VARCHAR(255) NOT NULL,
    source_rows      BIGINT,
    target_rows      BIGINT,
    null_primary_key BIGINT,
    duplicate_keys   BIGINT,
    missing_rows     BIGINT,
    extra_rows       BIGINT,
    cdc_inserts      BIGINT,
    cdc_updates      BIGINT,
    cdc_deletes      BIGINT,
    status           VARCHAR(20) NOT NULL,        -- PASS | FAIL | ERROR
    issues           TEXT,                        -- '; '-joined issue codes
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_validation_result_run ON validation_result(run_id);

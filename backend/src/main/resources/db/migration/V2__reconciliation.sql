-- Data validation / reconciliation results (issue #47).

CREATE TABLE reconciliation_run (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES migration_project(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL,        -- RUNNING | COMPLETED | FAILED
    total_tables  INTEGER NOT NULL DEFAULT 0,
    mismatched    INTEGER NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_recon_project ON reconciliation_run(project_id);

CREATE TABLE reconciliation_result (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES reconciliation_run(id) ON DELETE CASCADE,
    schema_name   VARCHAR(255) NOT NULL,
    table_name    VARCHAR(255) NOT NULL,
    source_count  BIGINT,
    target_count  BIGINT,
    difference    BIGINT,
    status        VARCHAR(20) NOT NULL,        -- MATCH | MISMATCH | ERROR
    error         TEXT
);
CREATE INDEX idx_reconres_run ON reconciliation_result(run_id);

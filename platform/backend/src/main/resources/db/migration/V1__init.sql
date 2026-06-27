-- Metadata/state schema for the migration platform (issue #19).
-- Replaces the file-based JSON progress tracking with a durable, concurrent store.

CREATE TABLE db_connection (
    id              UUID PRIMARY KEY,
    name            VARCHAR(150) NOT NULL UNIQUE,
    db_type         VARCHAR(20)  NOT NULL,        -- SQLSERVER | POSTGRESQL
    host            VARCHAR(255) NOT NULL,
    port            INTEGER      NOT NULL,
    database_name   VARCHAR(255) NOT NULL,
    username        VARCHAR(255) NOT NULL,
    password_enc    TEXT         NOT NULL,        -- AES-GCM ciphertext; never returned by API (#43)
    options         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE migration_project (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(150) NOT NULL UNIQUE,
    description           TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT', -- DRAFT|READY|ACTIVE|ARCHIVED
    source_connection_id  UUID REFERENCES db_connection(id),
    target_connection_id  UUID REFERENCES db_connection(id),
    config                JSONB        NOT NULL DEFAULT '{}'::jsonb, -- snapshot mode, cdc opts, mappings
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE migration_job (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES migration_project(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'CREATED', -- CREATED|SNAPSHOT|RUNNING|PAUSED|STOPPED|FAILED|COMPLETED
    phase         VARCHAR(30),
    source_connector_name VARCHAR(200),
    sink_connector_name   VARCHAR(200),
    error         TEXT,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_project ON migration_job(project_id);

CREATE TABLE table_status (
    id            UUID PRIMARY KEY,
    job_id        UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    schema_name   VARCHAR(255) NOT NULL,
    table_name    VARCHAR(255) NOT NULL,
    phase         VARCHAR(20)  NOT NULL,        -- SCHEMA | DATA | CDC
    status        VARCHAR(20)  NOT NULL,        -- PENDING|IN_PROGRESS|COMPLETED|FAILED
    rows_synced   BIGINT       NOT NULL DEFAULT 0,
    error         TEXT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tablestatus_job ON table_status(job_id);

-- Seeds the Auth/audit epic (#16 AUTH3): record control-plane actions.
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    actor       VARCHAR(150) NOT NULL DEFAULT 'system',
    action      VARCHAR(100) NOT NULL,
    target      VARCHAR(255),
    details     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

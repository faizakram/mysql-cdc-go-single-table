-- Alerts (issue #52): connector failures, reconciliation drift, etc. with dedup + history.
CREATE TABLE alert (
    id          UUID PRIMARY KEY,
    project_id  UUID REFERENCES migration_project(id) ON DELETE SET NULL,
    dedup_key   VARCHAR(255) NOT NULL,
    severity    VARCHAR(20)  NOT NULL,        -- INFO | WARNING | CRITICAL
    type        VARCHAR(50)  NOT NULL,        -- CONNECTOR_FAILED | DRIFT | JOB_FAILED | ...
    message     TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'FIRING', -- FIRING | RESOLVED | ACKNOWLEDGED
    details     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Only one open (FIRING) alert per dedup key at a time.
CREATE UNIQUE INDEX uq_alert_open ON alert(dedup_key) WHERE status = 'FIRING';
CREATE INDEX idx_alert_created ON alert(created_at);
CREATE INDEX idx_alert_project ON alert(project_id);

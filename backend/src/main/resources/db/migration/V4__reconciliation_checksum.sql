-- Checksum / sampling validation (issue #48), extending the reconciliation tables.
ALTER TABLE reconciliation_run ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'COUNT'; -- COUNT | CHECKSUM
ALTER TABLE reconciliation_result ADD COLUMN sampled BIGINT;   -- rows sampled from source (CHECKSUM)
ALTER TABLE reconciliation_result ADD COLUMN missing BIGINT;   -- sampled PKs absent in target (CHECKSUM)

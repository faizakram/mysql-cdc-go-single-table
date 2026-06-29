-- Per-table snapshot progress, ETA and lag (#185).
-- total_rows : source row-count estimate (catalog stats) captured at job start, for %-complete / ETA.
-- last_lag_ms: most recent per-table replication lag (ms), populated for CDC-streamed tables.
-- started_at : when this table's sync began, for per-table throughput (rows/sec) and elapsed time.
ALTER TABLE table_status ADD COLUMN total_rows  BIGINT;
ALTER TABLE table_status ADD COLUMN last_lag_ms BIGINT;
ALTER TABLE table_status ADD COLUMN started_at  TIMESTAMPTZ;

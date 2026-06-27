# Database & Metadata-Store Design

The control plane persists all state in a PostgreSQL **metadata store** (embedded for dev, external for production). Schema is owned by **Flyway** migrations under `backend/src/main/resources/db/migration/`; Hibernate runs in `validate` mode (it never alters the schema).

## Entity-relationship overview

```
db_connection ──(source/target)──┐
                                 ▼
                          migration_project ──┬──< migration_job ──< table_status
                                              ├──< reconciliation_run ──< reconciliation_result
                                              ├──< alert            (ON DELETE SET NULL)
                                              └──< job_schedule

app_user   (standalone — auth/RBAC)
audit_log  (standalone — immutable action trail)
```

Deleting a project cascades to its jobs (and their `table_status`), reconciliation runs (and results), and schedules. Alerts keep history via `ON DELETE SET NULL`.

## Tables

### `db_connection` — source/target databases (V1)
Entity: `DbConnection`. Columns: `id` (UUID PK), `name` (unique), `db_type`, `host`, `port`, `database_name`, `username`, `password_enc` (AES-GCM, never exposed), `options` (JSONB), `created_at`, `updated_at`.

### `migration_project` — top-level project (V1)
Entity: `MigrationProject`. Columns: `id` (PK), `name` (unique), `description`, `status` (`DRAFT|READY|ACTIVE|ARCHIVED`), `source_connection_id` (FK→db_connection), `target_connection_id` (FK→db_connection), `config` (JSONB — snapshot mode, delete strategy, target schema, selected tables, naming, type overrides), `created_at`, `updated_at`.

### `migration_job` — a single run (V1)
Entity: `MigrationJob`. Columns: `id` (PK), `project_id` (FK→migration_project, **cascade**), `status` (`CREATED|SNAPSHOT|RUNNING|PAUSED|STOPPED|FAILED|COMPLETED`), `phase`, `source_connector_name`, `sink_connector_name`, `error`, `started_at`, `finished_at`, `created_at`, `updated_at`. Index: `(project_id)`.

### `table_status` — per-table progress (V1)
Entity: `TableStatus`. Columns: `id` (PK), `job_id` (FK→migration_job, **cascade**), `schema_name`, `table_name`, `phase` (`SCHEMA|DATA|CDC`), `status` (`PENDING|IN_PROGRESS|COMPLETED|FAILED`), `rows_synced` (BIGINT), `error`, `updated_at`. Index: `(job_id)`. Updated live by `ProgressTracker`.

### `audit_log` — immutable action trail (V1)
Entity: `AuditLog`. Columns: `id` (PK), `actor` (username or `system`), `action` (e.g. `JOB_START`, `PROJECT_CREATE`, `LOGIN`), `target`, `details` (JSONB), `created_at`. Retention sweep removes entries older than `AUDIT_RETENTION_DAYS`.

### `reconciliation_run` — validation run (V2; `mode` added V4)
Entity: `ReconciliationRun`. Columns: `id` (PK), `project_id` (FK, **cascade**), `status` (`RUNNING|COMPLETED|FAILED`), `mode` (`COUNT|CHECKSUM`), `total_tables`, `mismatched`, `started_at`, `finished_at`. Index: `(project_id)`.

### `reconciliation_result` — per-table result (V2; `sampled,missing` V4; `changed` V6)
Entity: `ReconciliationResult`. Columns: `id` (PK), `run_id` (FK→reconciliation_run, **cascade**), `schema_name`, `table_name`, `source_count`, `target_count`, `difference`, `sampled`, `missing`, `changed`, `status` (`MATCH|MISMATCH|ERROR|SKIPPED`), `error`. Index: `(run_id)`.

### `app_user` — accounts & RBAC (V3)
Entity: `AppUser`. Columns: `id` (PK), `username` (unique), `password_hash` (BCrypt), `role` (`ADMIN|OPERATOR|VIEWER`, default `OPERATOR`), `enabled` (default true), `created_at`.

### `alert` — incidents (V5)
Entity: `Alert`. Columns: `id` (PK), `project_id` (FK→migration_project, **set null**), `dedup_key`, `severity` (`INFO|WARNING|CRITICAL`), `type`, `message`, `status` (`FIRING|RESOLVED|ACKNOWLEDGED`), `details` (JSONB), `created_at`, `updated_at`. Partial unique index `uq_alert_open(dedup_key) WHERE status='FIRING'` — one open alert per key. Indexes on `created_at`, `project_id`.

### `job_schedule` — cron schedules (V7)
Entity: `JobSchedule`. Columns: `id` (PK), `project_id` (FK, **cascade**), `kind` (`FULL_LOAD|VALIDATION`), `cron` (6-field), `enabled`, `last_run_at`, `last_status`, `next_run_at`, `created_at`, `updated_at`. Indexes: `(project_id)`, `(enabled,next_run_at)` for due-schedule lookups.

## Migration timeline

| Version | File | Change |
|---|---|---|
| V1 | `V1__init.sql` | `db_connection`, `migration_project`, `migration_job`, `table_status`, `audit_log` |
| V2 | `V2__reconciliation.sql` | `reconciliation_run`, `reconciliation_result` |
| V3 | `V3__users.sql` | `app_user` |
| V4 | `V4__reconciliation_checksum.sql` | + `mode`, `sampled`, `missing` |
| V5 | `V5__alerts.sql` | `alert` |
| V6 | `V6__reconciliation_changed.sql` | + `changed` |
| V7 | `V7__scheduling.sql` | `job_schedule` |

## Conventions

- **UUID** primary keys (assigned via `@PrePersist`).
- **`TIMESTAMPTZ`** timestamps with `now()` defaults; `@PreUpdate` maintains `updated_at`.
- **JSONB** for flexible config/details/options (no schema change to add a project setting).
- **Cascading deletes** keep the store consistent when a project is removed; alerts/audit preserve history.

> The **metadata store is separate** from the databases being migrated. It holds platform state only — never your business data.

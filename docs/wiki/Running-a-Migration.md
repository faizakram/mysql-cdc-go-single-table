# Running a Migration

An end-to-end runbook: from two database connections to a live, validated CDC migration. This mirrors the CDC lifecycle the platform manages for you.

## Prerequisites

- The platform is running ([Getting Started](Getting-Started.md)).
- A **source** database with CDC prerequisites enabled (see [CDC prerequisites](#cdc-prerequisites-by-engine) below).
- A **target** database you can write to, with the target schema present.
- Both reachable from the containers (use `host.docker.internal` for host databases).

## Lifecycle overview

```
Create connections → Create project → Select tables → (Map types) → Dry-run
   → Start job  ──snapshot──▶  RUNNING (CDC streaming)  → Validate  → Monitor
```

A migration job has these statuses: `CREATED → SNAPSHOT → RUNNING → (PAUSED) → STOPPED/COMPLETED/FAILED`. The **snapshot** phase copies existing rows; once Debezium reports the snapshot complete, the platform transitions the job to **RUNNING** and streams ongoing changes (CDC).

---

## Step 1 — Create connections

**Connections → New connection.** Create one for the **source** and one for the **target**.

| Field | Notes |
|---|---|
| Engine | SQL Server, PostgreSQL, MySQL, Oracle, Db2, or MongoDB (source only). |
| Host | Use `host.docker.internal` for a DB on your host machine. |
| Port | e.g. 1433 (SQL Server), 5432 (Postgres), 3306 (MySQL). |
| Database | The database/catalog name. |
| Username / Password | Stored **AES-256-GCM encrypted**; never returned by the API. |
| TLS / SSL mode | Set per engine (e.g. SQL Server: Encrypt + Trust server cert for dev; Postgres: `disable`/`require`). |

Click **Test connection** — it should report success with a latency. Then **CDC readiness** (on the connection) checks engine-specific prerequisites and tells you exactly what to fix.

## Step 2 — Create a project

**Projects → New project.** Link the source and target connections and set the CDC configuration:

| Setting | Meaning |
|---|---|
| **Snapshot mode** | `initial` (snapshot existing rows, then stream) · `schema_only` (no row copy, stream new changes) · `no_data`. |
| **Delete strategy** | `SOFT` (writes a `__cdc_deleted=true` flag column on the target) or `HARD` (physically deletes the row). |
| **Target schema** | The schema rows land in (e.g. `public`). **It must already exist** — the sink creates tables, not schemas. |
| **Naming strategy** | `PRESERVE` (default, exact names) or `SNAKE_CASE` / `CAMEL_CASE` / `PASCAL_CASE` / `UPPER_CASE` (applied by a custom SMT). |
| **Type overrides / UUID / JSON columns** | Optional per-column hints fed to the type-conversion transform. |

## Step 3 — Select tables

Open the project's **Tables** drawer. The platform discovers the source schema (tables, columns, PKs). Select the tables to migrate. **Skip system tables** (e.g. SQL Server's `systranschemas`) — they aren't CDC-capturable and will fail the source connector.

## Step 4 — (Optional) Review type mapping, recommendations & profile

The **Mapping** drawer has three tabs:
- **Type mapping** — proposed target types per column; override any, or tag a column as UUID/JSONB. Columns that map lossily (e.g. SQL Server `geography`, `hierarchyid`, `sql_variant`) are flagged.
- **Recommendations** — type advice across all selected tables, with confidence levels.
- **Profile & PII** — sampled column statistics (null %, distinct, min/max) and PII flags (email/SSN/phone/…).

## Step 5 — Dry-run

From the project actions, run **Dry-run**. It validates the whole setup *without deploying anything*: source/target connectivity, the migration plan (table count, dependency levels, estimated duration), and any blockers/warnings. Resolve blockers before starting.

## Step 6 — Start the job

Open **Runs**, create a run, and click **Start**. The platform:
1. Generates a Debezium **source** connector + a Debezium **JDBC sink** connector config.
2. Deploys both to Kafka Connect.
3. Seeds per-table status rows (`PENDING`) and sets the job to `SNAPSHOT`.

Watch the **Runs** drawer: each table shows **phase** (DATA → CDC), **status** (PENDING → IN_PROGRESS → …), and a live **rows synced** count (read from the Kafka topic offsets). When the snapshot finishes, the job flips to **RUNNING** and changes stream continuously.

## Step 7 — Validate

Open the **Validate** drawer:
- **Reconciliation** — `COUNT` (row counts, soft-delete aware) or `CHECKSUM` (samples PKs from the source and verifies presence + content on the target). Drift trend and CSV export included.
- **Integrity report** — deeper checks on the target: null primary keys, duplicate keys, missing/extra rows, per-table PASS/FAIL, CSV export.

You can also enable **scheduled validation** (drift detection) to run reconciliation automatically while the migration is active.

## Step 8 — Monitor & operate

- **Dashboard** — pipeline health, lag, and the job queue.
- **Runs** — pause/resume/stop, per-table progress.
- **Errors/Logs** drawer — failed connector task traces, with an automatic **remediation hint** for each.
- **Grafana** (http://localhost:3001) — lag, connector up/down, job metrics over time.

### Re-run a full load

Need a clean re-snapshot (e.g. after fixing a mapping)? Use **Re-run full load** on the run. It stops the source connector, resets its committed offsets, and resumes — Debezium snapshots from scratch and the sink upserts (idempotent on primary key).

---

## CDC prerequisites by engine

The **CDC readiness** check verifies these for you, but for reference:

| Engine | Requirements |
|---|---|
| **SQL Server** | SQL Server **Agent** running; CDC enabled at DB level (`sys.sp_cdc_enable_db`) and per table (`sys.sp_cdc_enable_table`). The login needs CDC read access. |
| **PostgreSQL** | `wal_level = logical`; a role with `REPLICATION`; logical replication slot + publication (the connector can create these). |
| **MySQL** | Binlog enabled (`log_bin`, `binlog_format = ROW`, `binlog_row_image = FULL`); a user with `REPLICATION SLAVE`, `REPLICATION CLIENT`. MySQL 8.0 line recommended. |
| **Oracle** | LogMiner + supplemental logging; appropriate grants. |
| **Db2** | Transaction-log CDC configured for the captured tables. |
| **MongoDB** | A replica set (change streams require it); source-only. |

## Common gotchas

- **Sink fails with `schema "X" does not exist`** — the JDBC sink creates *tables*, not *schemas*. Pre-create the target schema, or set the project's target schema to one that exists (e.g. `public`). See [Troubleshooting](Troubleshooting.md).
- **Job won't deploy / `I/O error POST /connectors`** — the data plane (Kafka Connect) isn't up. With `docker-compose.full.yml` it always is; if you run pieces separately, start Connect first.
- **A captured table has an exotic column type** — types like `geography`/`hierarchyid`/`sql_variant` are flagged in Mapping; verify their representation on the target after the first load.

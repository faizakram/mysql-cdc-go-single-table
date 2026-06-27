# CDC Hardening (epic #7)

How the platform handles three production concerns for SQL Server → PostgreSQL CDC:
schema evolution (#26), large-table snapshots (#27), and replication lag / backpressure (#28).
All knobs live in a project's `config` and flow into the generated connectors via
`ConnectorConfigService`; the relevant ones are editable from the UI (Configure drawer).

## #26 — Schema evolution

The JDBC sink's `schema.evolution` is configurable per project (`config.schemaEvolution`,
default `basic`):

- **`basic`** — the sink issues additive DDL (`ALTER TABLE … ADD COLUMN`) on the target when an
  incoming record carries a field the target table lacks. New nullable columns propagate
  automatically. This is the default.
- **`none`** — the sink never alters the target; unknown fields are dropped. Use when the target
  DDL is managed out-of-band and drift must be explicit.

### Important SQL Server caveat (capture instances)

SQL Server CDC captures a **fixed column set** at the moment a table is enabled. Simply running
`ALTER TABLE … ADD COLUMN` on the source does **not** make the new column appear in change events.
You must create a **second capture instance** for the altered table; Debezium detects it and
switches over automatically (a table may have at most two capture instances at once):

```sql
ALTER TABLE dbo.Employee ADD MiddleName NVARCHAR(50) NULL;
EXEC sys.sp_cdc_enable_table
     @source_schema = N'dbo', @source_name = N'Employee',
     @role_name = NULL, @supports_net_changes = 0,
     @capture_instance = N'dbo_Employee_v2';   -- new instance carries the new column
```

After the new instance exists, inserts/updates that touch the new column flow through, and a
`basic`-evolution sink adds the matching column on the target.

### Verified end-to-end (2026-06-26)

Against the live data plane (`docker-compose.dataplane.yml`): added `MiddleName` to
`dbo.Employee`, created `dbo_Employee_v2`, inserted a row and updated an existing one. The target
`public.employee` table gained a `middle_name` column automatically (snake-cased by the SMT), the
new row landed with its value, and the updated row reflected the change — with no manual target DDL.

## #27 — Large-table snapshot strategy

The source connector exposes two tuning knobs (per project `config`):

| config key            | connector property      | default | purpose                                   |
|-----------------------|-------------------------|---------|-------------------------------------------|
| `snapshotMaxThreads`  | `snapshot.max.threads`  | `1`     | parallel snapshot workers for big tables  |
| `snapshotFetchSize`   | `snapshot.fetch.size`   | `2000`  | JDBC fetch size during the initial scan   |

Combined with `snapshotMode` (`initial` / `schema_only` / `no_data`), this lets large initial
loads run in parallel with a larger fetch size while keeping a safe single-threaded default.
Verified live: a project configured with `snapshot.max.threads=4`, `snapshot.fetch.size=5000`
deployed those exact values onto the running connector.

## #28 — Replication lag & alerting

- **Metric (#50):** `MonitoringService` reports per-project sink consumer-group lag (Kafka
  `AdminClient` end-offsets minus committed offsets), surfaced on the dashboard.
- **Alerting (#28):** `AlertMonitor` raises a `WARNING` `LAG` alert (deduped per project) when a
  project's lag exceeds `platform.alerts.lag-threshold` (`ALERTS_LAG_THRESHOLD`, `0` = disabled),
  and resolves it automatically once lag falls back under the threshold. Alerts route to the
  configured webhook (Slack/Teams) or the log.
- **Backpressure:** lag is the backpressure signal. Sustained lag is the trigger to scale
  `tasks.max`, raise sink batch sizes, or throttle the source — the alert makes that condition
  visible rather than silent.

# Least-privilege database accounts (issue #46)

The platform must not use `sa` (SQL Server) or the `postgres` superuser. This document defines the
minimal accounts the runtime needs and how to apply and verify them. Ready-to-run scripts:
[`deploy/sql/mssql-least-privilege.sql`](../deploy/sql/mssql-least-privilege.sql) and
[`deploy/sql/postgres-least-privilege.sql`](../deploy/sql/postgres-least-privilege.sql).

## Principle

Separate **one-time admin actions** (enabling CDC, creating schemas) — performed by a DBA — from the
**runtime account** the connectors and control plane use continuously. The runtime account gets only
what it needs for steady-state replication and validation.

## Permission model

| Account | Used by | Needs | Must NOT have |
|---|---|---|---|
| **`cdc_app`** (SQL Server source) | Debezium source connector; control-plane test/discovery/reconciliation | Read source tables (`db_datareader` or scoped `SELECT`); read CDC change data (`SELECT`/`EXECUTE` on `cdc` schema); `VIEW DATABASE STATE`; membership in the CDC gating role if one is used | `sysadmin`/`db_owner`; ability to run `sp_cdc_enable_*`; any write |
| **`migration_app`** (PostgreSQL target) | Debezium JDBC sink; control-plane reconciliation | `CONNECT`; `USAGE`+`CREATE` on the **target schema** (sink auto-creates tables); `SELECT/INSERT/UPDATE/DELETE` on tables in that schema (incl. future tables via default privileges) | `SUPERUSER`, `CREATEDB`, `CREATEROLE`; access to other schemas; access to the platform metadata DB |
| **platform metadata owner** | the control plane's own Postgres store | owns the `migration_platform` DB (Flyway migrations + JPA) | reuse as a migration target account |

Why the sink needs `CREATE` on the schema: the JDBC sink with `schema.evolution=basic` creates and
alters target tables. To remove even that, pre-create the target tables (schema-replication step) as
the DBA and drop `CREATE` from `migration_app` — tracked alongside the schema/DDL work (#33).

## Apply

1. Edit each script: replace `<SOURCE_DB>` / `<TARGET_DB>` / `<TARGET_SCHEMA>` and the passwords.
2. Run `mssql-least-privilege.sql` as a SQL Server admin; run `postgres-least-privilege.sql` as a
   Postgres superuser/owner.
3. In the platform UI, create the **source** and **target** connections using `cdc_app` /
   `migration_app` (never `sa`/`postgres`). The generated connector configs and all JDBC paths then
   use these accounts automatically.

## Verify the pipeline under reduced privileges

- **Connections** → *Test* both: should succeed (read/connect).
- **Tables** → discovery lists tables and CDC status (reads `INFORMATION_SCHEMA` + `cdc`).
- **Runs** → *Start*: the source connector snapshots/streams (reads), the sink creates tables and
  upserts (DML within the target schema). Watch the **dashboard** — both connectors should be RUNNING.
- **Validate** (counts + checksum): read-only on both sides; should run clean.
- Negative checks: `cdc_app` cannot `INSERT` into a source table; `migration_app` cannot create a
  database or read another schema — confirm these are denied.

## Defaults to change

The dev defaults (`sa`, `postgres`) remain only for local Docker convenience. Production deployments
must use these least-privilege accounts and strong, rotated passwords (stored via the secrets path,
#43), with TLS enforced (#44).

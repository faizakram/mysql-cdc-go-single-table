# Heterogeneous Database Migration (CDC) — Architecture Review & Gap Analysis

**Date:** 2026-06-26
**Scope reviewed:** `debezium-setup/` (MS SQL Server → PostgreSQL CDC, the product) and the root Go MySQL→MySQL tool (secondary/legacy).
**Companion artifact:** GitHub Project [Heterogeneous Database Migration (CDC) #22](https://github.com/users/faizakram/projects/22) — the prioritized backlog derived from this review.

---

## 1. What the project is

The repository contains **two unrelated CDC solutions**:

| | **Debezium setup (the product)** | **Root Go tool (legacy/secondary)** |
|---|---|---|
| Path | `debezium-setup/` | `src/`, `go.mod` |
| Direction | MS SQL Server → PostgreSQL | MySQL → MySQL |
| CDC mechanism | Debezium SQL Server connector → Kafka → JDBC sink | MySQL binlog parsing (`go-mysql`) |
| Transforms | Custom Java SMTs (snake_case, type conversion) | UTF-32 → UTF-8 charset conversion |
| State | JSON files on disk | Checkpoint table in target |

The naming (`mysql-cdc-go-single-table`) reflects the legacy Go tool, **not** the current product, which is the Debezium MS SQL → PostgreSQL pipeline. This mismatch is itself a source of confusion (tracked: disposition of the Go tool).

---

## 2. Current architecture (Debezium product)

```
            ┌─────────────┐   CDC    ┌────────┐   topics   ┌──────────────┐   JDBC    ┌────────────┐
 MS SQL ───▶│ Debezium SQL│─────────▶│ Kafka  │───────────▶│ Debezium JDBC│──────────▶│ PostgreSQL │
 (CDC on)   │ Server src  │          │ (1 brk)│            │ sink + SMTs  │           │ (target)   │
            └─────────────┘          └────────┘            └──────────────┘           └────────────┘
                  ▲                       ▲                       ▲
                  │ enable CDC            │ Kafka Connect REST :8083 (curl)
       ┌──────────┴──────────┐   ┌────────┴───────────────────────────────┐
       │ enable-cdc-*.sql     │   │ Python: replicate-schema.py (DDL),     │
       │ auto-enable trigger  │   │         sync-data.py (full load),      │
       └──────────────────────┘   │         progress.py (JSON state)       │
                                   └────────────────────────────────────────┘
   Operator surface today: docker compose, curl to :8083, python scripts, Kafka UI (:8080), docker logs.
```

**Components**

- **Infrastructure** (`docker-compose.yml`): Zookeeper, single Kafka broker, single Kafka Connect (Debezium 2.5), Kafka UI. Replication factor 1 throughout.
- **Source connector** (`connectors/mssql-source.json`): `SqlServerConnector`, `snapshot.mode=initial`, `tombstones.on.delete=true`, regex router to strip the `db.schema.table` prefix.
- **Sink connector** (`connectors/postgres-sink.json`): `JdbcSinkConnector`, `insert.mode=upsert`, `delete.enabled=true`, SMT chain: unwrap → rename `__deleted`→`__cdc_deleted` → cast → snake_case (key+value) → type conversion.
- **Custom SMTs** (`custom-smt/`): `SnakeCaseTransform` (PascalCase/acronym-aware → snake_case, recursive on structs) and `TypeConversionTransform` (tags STRING fields as `io.debezium.data.Uuid` / `Json` by explicit column list or regex pattern).
- **Python control scripts** (`scripts/`): `replicate-schema.py` (introspects `sys.columns`, maps types, `CREATE TABLE`), `sync-data.py` (direct full-load MSSQL→PG with batching, resume), `progress.py` (file-based table-by-table status), plus shell deploy scripts and SQL to enable CDC.

**Three sync layers, sequential, non-overlapping:** (1) schema creation, (2) optional full-load via Python, (3) continuous CDC via Debezium.

---

## 3. Strengths

- **Sound CDC backbone.** Debezium + Kafka is an industry-standard, replayable, log-based CDC foundation with exactly-once-ish delivery and decoupled source/sink.
- **Automatic schema replication.** `replicate-schema.py` removes manual DDL; a broad MSSQL→PostgreSQL type map preserves precision/length for the common cases, and UNIQUEIDENTIFIER→UUID is native.
- **Thoughtful transforms.** The snake_case SMT correctly handles acronyms (`HTTPSConnection`→`https_connection`) and nested structs; type-conversion SMT is config- and pattern-driven.
- **Resume capability.** `progress.py` + `--resume`/`--start-from-table`/`--reset` make the full-load restartable with partial-table truncation — valuable for large datasets.
- **Operational ergonomics for a prototype.** `deploy-all.sh` is idempotent for connectors (deletes before re-create), auto-detects ODBC drivers, and the auto-enable-CDC DDL trigger picks up new tables.
- **The legacy Go tool is independently solid** for its niche (cursor-based parallel load, pre-snapshot binlog capture to avoid gaps, graceful shutdown, health endpoint).

---

## 4. Issues identified

### 4.1 Correctness / CDC
- **Delete-semantics conflict (high).** Schema adds a `__cdc_deleted` soft-delete column, but the sink runs `delete.enabled=true` + tombstones, so deletes physically remove rows. The two strategies contradict; intended behavior is ambiguous (commit `5a6d90b` "switched to hard delete" while the column remains).
- **No schema-change propagation (high).** Source `ALTER TABLE` is not handled; changes require stopping connectors and re-replicating — drift and downtime risk.
- **Type-conversion length bugs (high).** `replicate-schema.py` halves char/nvarchar lengths (`length//2`) and does **not** handle `max_length = -1` (VARCHAR(MAX)/NVARCHAR(MAX)) → wrong/negative lengths and possible truncation.
- **Lossy/untested types (medium).** `xml`, `geography`, `geometry`, `hierarchyid`, `sql_variant` all collapse to `TEXT`; `image`/`varbinary(max)` untested.
- **Indexes/FKs/defaults/constraints dropped (medium).** Only columns + PK are replicated, hurting target performance and integrity.
- **No lag/backpressure measurement (medium).** Replication lag is not computed; problems are invisible until data is visibly stale.

### 4.2 Security (most severe cluster)
- **Plaintext credentials (high).** Passwords live in `.env` and are written into `connectors/*.json` on disk; `deploy-all.sh` hardcodes `sa` / `YourStrong@Passw0rd`.
- **TLS disabled (high).** `database.encrypt=false`; Kafka/PG traffic in plaintext.
- **Unauthenticated Kafka Connect REST (high).** Anyone reaching `:8083` can deploy/destroy connectors and read configs (including secrets).
- **Over-privileged accounts (medium).** `sa` and a superuser-like Postgres account.

### 4.3 Operability / production readiness
- **No web UI / no API.** Everything is CLI + curl + `docker logs` + manual SQL. This is the central gap the project aims to close.
- **File-based state.** Progress in local JSON is invisible, non-concurrent, and lost on container restart — blocks multi-project, history, and UI.
- **Destructive deploy ops (high).** `DROP SCHEMA … CASCADE` (deploy) and `DROP TABLE … CASCADE` (replication) would destroy data if pointed at a live target.
- **No HA.** Single Kafka broker, single Connect, replication factor 1 — single point of failure; no DR/backup story for offsets/schema-history/metadata.
- **No monitoring/alerting.** No metrics export, dashboards, or alerts; failures go unnoticed.
- **No tests, no CI.** SMTs and Python scripts have no unit tests; no end-to-end tests; SMT jar built and `docker cp`'d by hand.
- **No authN/Z, no audit.** Anyone with access controls migrations.
- **Documentation sprawl.** 13 overlapping markdown guides with no single authoritative runbook.

---

## 5. Recommendations (target architecture)

Evolve from "scripts + connectors" to a **platform with a control plane and data plane**:

1. **Control plane:** a backend service exposing a versioned REST API that wraps Kafka Connect, the schema/load operations, and a **metadata/state database** (projects, connections, jobs, per-table status, history) — replacing the JSON files.
2. **Data plane:** the existing Debezium/Kafka/Connect pipeline, hardened — HA Kafka (RF>1), distributed Connect, schema-evolution enabled, consistent delete strategy, configurable type-mapping engine shared by schema creation and the sink.
3. **Web UI:** a browser console over the API that fully replaces the CLI — connection config + validation, schema discovery + table selection, type-mapping editor, migration/CDC config, real-time dashboard with start/pause/resume/stop, live logs, history, and validation results.
4. **Cross-cutting:** secrets manager + TLS + authenticated Connect; authN/RBAC/audit; metrics→Prometheus + Grafana + alerting; data validation (row counts, checksums, drift); scheduling + job queue; CI/CD with security scanning; consolidated docs.

A C4-level target diagram and migration path are tracked under the **Documentation** epic.

---

## 6. Backlog mapping

This review is operationalized as **13 epics / 51 task issues** in [Project #22](https://github.com/users/faizakram/projects/22), each with business objective, technical requirements, acceptance criteria, dependencies, priority, effort, area, and type, wired as native sub-issues.

| Epic | Theme | Headline items |
|---|---|---|
| Platform Architecture & Foundation | target arch, metadata store, legacy-tool disposition | high |
| Orchestration Backend & API | control-plane service, project/connection CRUD, job control, OpenAPI | high |
| CDC Engine Hardening | delete conflict, schema evolution, snapshots, lag, type coverage | high |
| Schema Discovery, Mapping & Type Conversion | discovery API, mapping engine, length bugs, indexes/FKs | high |
| Browser-Based Web UI | 9 screens replacing the CLI end-to-end | high |
| Security, Secrets & Compliance | secrets, TLS, Connect auth, least privilege | high |
| Data Validation & Reconciliation | row counts, checksums, drift | high |
| Monitoring, Logging & Alerting | metrics, dashboards, alerts | high |
| Scheduling & Job Orchestration | scheduler, queue/concurrency | medium |
| User Management & Access Control | authN, RBAC, audit | high |
| Testing & QA | unit, E2E pipeline, UI/API, performance | high |
| CI/CD, Deployment & Production Readiness | CI, remove destructive ops, HA, DR | high |
| Documentation | architecture, API, consolidated runbook | medium |

**Development rule:** implementation begins only after this backlog is reviewed and prioritized. Suggested first wave (foundations + highest-risk): target architecture → metadata store → backend API → secrets/TLS/Connect auth → delete-semantics fix → remove destructive ops → mapping-engine + length-bug fix, then the UI MVP.

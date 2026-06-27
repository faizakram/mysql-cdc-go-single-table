# Architecture

The platform is split into a **control plane** (what you operate) and a **data plane** (what moves the data). The control plane never sits in the data path — it generates connector configs, deploys them to Kafka Connect, and observes health.

## High-level components

<img width="900" height="500" alt="a20ac728-876e-4920-9588-8a88332170ed" src="https://github.com/user-attachments/assets/e7b18dfe-3cef-4c56-b2b3-97642e807eb2" />

## Backend package map

Under `backend/src/main/java/com/migration/platform/`:

| Package | Responsibility |
|---|---|
| `connection` | `DbType` enum + `EngineCatalog` (engine specs: driver, CDC style, Debezium connector, capabilities); `DbConnection` entity; connection CRUD + test; `SchemaDiscoveryService`; `TypeMappingService`/`TypeMappingMatrix`; `JdbcSupport`/`MongoSupport`; `SchemaReplicationService` (constraints/index DDL). |
| `connector` | `ConnectorConfigService` (builds source + sink configs); `SourceConnectorStrategy` interface + per-engine implementations; `MigrationConfig` (typed view of project config); `DeleteStrategy`; `NamingStrategy`; `ConnectorSecretProperties`. |
| `project` | `MigrationProject` entity + `ProjectService` (CRUD, source→target pairing validation, paged/filtered list). |
| `job` | `MigrationJob` + `JobStatus`/`JobTransitions` (state machine); `JobService` (start/pause/resume/stop/reload); `TableStatus` (per-table progress); `ProgressTracker` (scheduled progress + snapshot-completion detection). |
| `connect` | `KafkaConnectClient` — thin REST proxy to Kafka Connect (create/pause/resume/stop/restart/delete, status, offsets, delete-offsets). |
| `monitoring` | `MonitoringService` (health aggregation), `LagService` (consumer-group lag + per-table record counts via Kafka Admin), `PlatformMetrics` (Prometheus gauges). |
| `reconciliation` | `ReconciliationService` (COUNT/CHECKSUM), run/result entities, cron scheduler. |
| `validation` | `ValidationService` (null PKs, dup keys, missing/extra rows). |
| `planning` | `MigrationPlanService` (FK-ordered plan, parallel levels, risk, duration/cost estimate). |
| `intelligence` | Recommendations, cost estimate, remediation hints. |
| `quality` | Column profiling + PII detection. |
| `scheduling` | `ScheduleService` (cron schedules), `JobOrchestrator` (concurrency-bounded execution), sweeper. |
| `alert` | `AlertService` + monitor + notifier (webhook). |
| `audit` | `AuditService` + `AuditLog` (immutable action trail, retention sweep). |
| `auth` | `SecurityConfig`, `JwtService`, `JwtAuthFilter`, `AuthService`, `AppUser`, `Role`, `UserService`. |
| `config` | `PlatformProperties` (`@ConfigurationProperties`), `WebConfig` (Connect REST client). |
| `common` | `CryptoService` (AES-256-GCM), `PageResponse`, `ApiError`/`GlobalExceptionHandler`, `Names`. |

## Multi-engine design

Each engine is one entry in **`EngineCatalog`**: default port, JDBC driver, JDBC URL template, `canSource`/`canSink`, Debezium connector class, and CDC style (`TRANSACTION_LOG`, `BINLOG`, `LOGICAL_DECODING`, `LOG_MINER`, `CHANGE_STREAM`).

Source connectors are built by a per-engine **`SourceConnectorStrategy`**:

| Engine | CDC style | Notes |
|---|---|---|
| MySQL | Binlog | Unique `database.server.id` per connector; `snapshot.fetch.size`. |
| PostgreSQL | Logical decoding (pgoutput) | Replication slot + publication. |
| SQL Server | Transaction-log CDC | LSN tracking; requires SQL Agent + CDC enabled. |
| Oracle | LogMiner | SCN tracking + supplemental logging. |
| Db2 | Transaction-log CDC | — |
| MongoDB | Change streams | Source-only; document envelopes flattened by an SMT. |

The **sink** is engine-agnostic (Debezium JDBC sink). `ConnectorConfigService` assembles a transform (SMT) chain on the sink:

1. **route** — `RegexRouter` maps any topic `prefix.<namespace…>.<table>` to the final `table` name (works for every source's topic depth).
2. **unwrap** — `ExtractNewRecordState` (relational) or `ExtractNewDocumentState` (MongoDB).
3. **renameDeleted / castDeleted** — SOFT delete only: `__deleted` → `__cdc_deleted` (boolean).
4. **caseKey / caseValue** — non-PRESERVE naming, via the custom `SnakeCaseTransform`.
5. **typeConversion** — custom SMT for UUID/JSON columns.

Sink uses **upsert** (primary-key mode), `delete.enabled` for HARD deletes, and `schema.evolution=basic` to auto-create/alter target tables.

## Job lifecycle & progress

`JobService.start()` generates both connector configs, deploys them via `KafkaConnectClient`, sets the job to `SNAPSHOT`, and seeds `table_status` rows.

`ProgressTracker` runs on a schedule (default 15s) and, for each active job:
- Reads cumulative records produced per table from Kafka topic end-offsets (`LagService.recordsByTable`) → updates `rows_synced`.
- Reads the source connector's committed offsets (`KafkaConnectClient.connectorOffsets`) to detect snapshot completion (Debezium's `snapshot` flag).
- When the snapshot is done and the source is healthy, transitions the job `SNAPSHOT → RUNNING` (phase `cdc`).
- Surfaces failed source tasks as per-table `FAILED` + trace.

`reloadFull()` stops the source connector, resets its committed offsets (Kafka Connect 3.6+ `DELETE /offsets`), and resumes → a clean re-snapshot.

## Request flow: "Start a migration"

1. UI `POST /api/v1/jobs/{id}/start`.
2. `JobService` loads project + connections, calls `ConnectorConfigService.sourceConnector(...)` and `.sinkConnector(...)`.
3. `KafkaConnectClient.createConnector(...)` deploys both via `POST :8083/connectors`.
4. Job → `SNAPSHOT`; `table_status` seeded `PENDING`.
5. Debezium snapshots the source → Kafka topics; the JDBC sink applies SMTs and upserts into `target.<schema>.<table>`.
6. `ProgressTracker` advances per-table progress and flips the job to `RUNNING` when the snapshot completes.

## Design principles

- **Control/data-plane separation** — the backend orchestrates and observes; it is not in the replication path.
- **Generic routing** — one RegexRouter handles every engine's topic topology.
- **Composable SMTs** — delete handling, naming, and type conversion are independent transforms.
- **Secrets at rest** — connection passwords are AES-256-GCM encrypted and never returned ([Security](Security.md)).
- **Observability built in** — per-table progress, consumer-group lag, Prometheus metrics, audit log.

---

## Current vs. target architecture

The diagram above is the **current** architecture as it ships in `deploy/docker-compose.full.yml`: a single backend, an embedded-free metadata Postgres container, single-broker Kafka, and a single-worker Kafka Connect. That topology is for local runs, demos, and correctness — **not** production throughput or availability.

### Target / production architecture (HA)

For production, the same components run with no single point of failure:

```
            ┌──────────── Ingress / LB (TLS) ────────────┐
            ▼                                             ▼
   Frontend (nginx, N replicas)            Backend (Deployment, 2+ replicas, HPA 2→6)
                                                  │  (stateless — all state in the DB)
                                                  ▼
                              External HA Postgres (primary + replica, PITR)   ← metadata
                                                  │
                                                  ▼  Connect REST
                              Kafka Connect cluster (Strimzi, 2+ workers)
                                                  │   configs + offsets in Kafka topics (RF=3)
                                                  ▼
                              Kafka (Strimzi, 3 brokers, RF=3, min.insync=2)
                                       ▲                         │
                         Debezium source                   JDBC sink
                                       │                         ▼
                              Source DB (HA)            Target DB (HA)
```

| Layer | Production strategy | Survives |
|---|---|---|
| Backend | Stateless `Deployment`, 2+ replicas, HPA, PodDisruptionBudget, topology spread | pod/node loss, rolling upgrades |
| Metadata Postgres | External HA (CloudNativePG / Patroni / RDS Multi-AZ), sync replication, PITR | primary failover (RPO ≈ 0) |
| Kafka | Strimzi 3-broker quorum, RF=3, `min.insync.replicas=2` | one broker loss, no data loss |
| Kafka Connect | Strimzi distributed cluster, 2+ workers; configs/offsets in replicated topics | worker loss → connectors rebalance, resume from committed offset |
| Source / target DBs | Customer-managed HA | DB failover; CDC resumes from last offset |

**Why the control plane scales safely:** all state is in the metadata Postgres, so any backend replica serves any request. The only multi-instance concern is the two background jobs (reconciliation, alert monitor) — run them under leader election so they fire once per cluster, not once per pod.

**Why a Connect *cluster* is the real availability win:** the single-worker dev data plane keeps offsets on a worker's disk; a Strimzi `KafkaConnect` cluster keeps connector configs and **source offsets** in replicated Kafka topics. If a worker dies, the cluster rebalances the connector to a survivor and it resumes from the last committed offset — no re-snapshot, no data loss.

### Migration path (dev → production)

1. **Externalize the metadata store** — set `PLATFORM_METADATA_EMBEDDED=false` and point at managed HA Postgres with PITR.
2. **Move secrets out of config** — set strong `PLATFORM_CRYPTO_KEY` / `JWT_SECRET`, and `CONNECT_SECRET_MODE=file`/`env` so DB passwords resolve from mounted secrets, not the Connect config topic.
3. **Replace the single-worker data plane** — deploy Kafka + Connect via the Strimzi operator (`deploy/k8s/30-kafka-connect-strimzi.yaml`); set topic RF=3.
4. **Scale the control plane** — run the backend `Deployment` with 2+ replicas behind an ingress doing TLS; gate background jobs with leader election.
5. **Add HA for source/target** and validate throughput (see [Scaling & Capacity Planning](Scaling-and-Capacity-Planning.md)) and recovery (see [Disaster Recovery](Disaster-Recovery.md)).

Apply order and manifests are in [Deployment](Deployment.md) and the `docs/HA-TOPOLOGY.md` deep-dive.

See also: [Deployment](Deployment.md) · [Scaling & Capacity Planning](Scaling-and-Capacity-Planning.md) · [Disaster Recovery](Disaster-Recovery.md) · [Database Schema](Database-Schema.md) · [API Reference](API-Reference.md) · [Configuration](Configuration.md).

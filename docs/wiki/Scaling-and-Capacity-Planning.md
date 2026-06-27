# Scaling & Capacity Planning

How the platform scales, the levers you have, sizing guidance, and the known limits. The control plane is light; the data plane (Kafka + Connect) is where throughput is won or lost.

## Scaling levers by layer

| Layer | Lever | Notes |
|---|---|---|
| **Control plane** (backend) | Horizontal — 2+ replicas behind a load balancer; HPA 2→6 on CPU | Stateless (all state in the metadata DB); any replica serves any request. Background jobs (reconciliation, alert monitor) must run under leader election so they fire once per cluster, not per pod. |
| **Metadata store** (Postgres) | Vertical — size for connection count + reconciliation query load; HA primary/replica | The component most likely to need sizing; the API itself is cheap. |
| **Kafka** | Brokers + partitions; RF=3, `min.insync.replicas=2` | More partitions per topic = more sink-task parallelism. |
| **Kafka Connect** | Workers + per-connector `tasks.max` | Add workers and raise `tasks.max` to scale streaming throughput. Offsets live in replicated Kafka topics, not on a worker. |
| **Snapshot (initial load)** | `snapshotMaxThreads`, `snapshotFetchSize` per project | Parallelize large initial loads. |

## Per-project tuning knobs

Set these in the project config (see [Configuration](Configuration.md) and the `docs/CDC-HARDENING.md` deep-dive):

| Project setting | Connector property | Default | Effect |
|---|---|---|---|
| `snapshotMaxThreads` | `snapshot.max.threads` | `1` | Parallel snapshot workers for big tables. |
| `snapshotFetchSize` | `snapshot.fetch.size` | `2000` | JDBC fetch size during the initial scan. |
| `snapshotMode` | `snapshot.mode` | `initial` | `initial` (copy + stream) / `schema_only` / `no_data`. |
| `tasksMax` | `tasks.max` | `1` | Source/sink task parallelism (needs enough topic partitions). |

> Verified live: a project with `snapshot.max.threads=4`, `snapshot.fetch.size=5000` runs large initial loads in parallel while the safe single-threaded default protects small ones.

## Capacity-planning guidance

Throughput is governed by three things: **initial volume** (rows to snapshot), **change rate** (events/sec during CDC), and **parallelism** (tasks × partitions).

- **Small (≤ ~10 tables, ≤ ~1M rows, low change rate):** defaults are fine — single worker, `tasks.max=1`, default snapshot settings. This is the local/`docker-compose.full.yml` profile.
- **Medium (10s of tables, 1–100M rows, moderate change):** raise `snapshotMaxThreads` (4–8) and `snapshotFetchSize` (5000+) for the initial load; give the sink topics multiple partitions and `tasks.max` ≥ partitions; run a 2–3 worker Connect cluster.
- **Large (100s of tables, 100M+ rows, high change rate):** multi-broker Kafka (RF=3), a Connect cluster sized so total `tasks.max` across connectors ≤ worker count × CPU; partition hot topics; consider splitting very large tables into their own project/connector; watch lag as the backpressure signal.

**Sizing checklist**
1. Estimate snapshot time = rows ÷ (rows/sec per thread × threads). Bump threads/fetch for the initial load, then they idle during streaming.
2. Size Kafka partitions for the busiest table's change rate; sink parallelism can't exceed partition count.
3. Size the metadata Postgres for `replicas × Hikari-pool` connections plus reconciliation queries.
4. Leave headroom: keep total Connect `tasks.max` below worker CPU capacity so rebalances don't thrash.

## Functional baselines (verified)

Every engine dimension has been exercised end-to-end through the platform (see `docs/CROSS-DB-TEST-MATRIX.md`):

| Source → Target | Type | Result |
|---|---|---|
| SQL Server → PostgreSQL | heterogeneous | ✅ snapshot + live CDC + soft-delete |
| PostgreSQL → PostgreSQL | homogeneous | ✅ logical decoding |
| MySQL → PostgreSQL | heterogeneous | ✅ binlog |
| MongoDB → PostgreSQL | non-relational source | ✅ change streams |
| SQL Server → MySQL | non-PG target | ✅ **10,503 rows** via the JDBC sink |

These are **functional** baselines (correctness across every dimension). Formal throughput/perf baselines (events/sec under load) are a future deliverable (#64) — size conservatively and measure with the lag metric until those land.

## Known limits & bottlenecks

- **Single-worker dev** (`docker-compose.full.yml`) uses RF=1 and is **not** for production throughput — it's for correctness/demos. Use a Strimzi Connect cluster (see [Architecture → Target architecture](Architecture.md#target--production-architecture-ha)) for scale.
- **Sink parallelism is capped by topic partitions** — raising `tasks.max` past the partition count does nothing.
- **Metadata Postgres** is the control-plane bottleneck under many replicas — size connections and reconciliation load.
- **Snapshot of very large tables** is the long pole in RTO; parallelize, and prefer `schema_only` when a full copy isn't needed.
- **MySQL 8.4** removed `SHOW MASTER STATUS` (breaks Debezium 2.5 MySQL source snapshot) — pin source MySQL ≤ 8.0 or use Debezium ≥ 2.6 (sink to MySQL 8.4 is fine).
- **Oracle / Db2** are unit-tested for config generation but not yet live-load-tested in this environment (image/licensing constraints) — validate throughput before production use.

See also: [Monitoring & Operations](Monitoring-and-Operations.md) (lag as the backpressure signal), [Disaster Recovery](Disaster-Recovery.md), and the `docs/HA-TOPOLOGY.md` / `docs/CDC-HARDENING.md` deep-dives.

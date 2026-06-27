# Monitoring & Operations

The full stack ships an observability suite — **Prometheus** (metrics), **Grafana** (dashboards), **Loki + promtail** (logs) — on the same network as the platform.

| Tool | URL | Purpose |
|---|---|---|
| Grafana | http://localhost:3001 (`admin`/`admin`) | Dashboards (lag, connector health, jobs) |
| Prometheus | http://localhost:9090 | Metrics store + queries |
| Loki | (internal :3100, via Grafana Explore) | Centralized container logs |
| Backend metrics | http://localhost:8090/actuator/prometheus | Raw Micrometer scrape endpoint |

## Metrics

The backend exposes JVM/HTTP/Hikari metrics plus custom `migration_*` gauges via Micrometer → `/actuator/prometheus`. `PlatformMetrics` refreshes per-project gauges every `METRICS_REFRESH_MS` (15s) by calling Kafka Connect:

- **Sink lag** — records the target is behind the source stream (sink consumer-group lag).
- **Connector up/down** — per project, derived from connector/task state.
- **Job counts by status** — from the metadata store.

Prometheus (`deploy/monitoring/prometheus.yml`) scrapes `backend:8090/actuator/prometheus` (job `migration-platform`). A second target, `connect:9404` (`kafka-connect-jmx`), is pre-wired for when you enable the Connect JMX exporter — it shows *down* until then, which is expected.

Quick checks:
```bash
# Is the backend being scraped?
curl -s 'http://localhost:9090/api/v1/query?query=up{job="migration-platform"}'
# Targets health
open http://localhost:9090/targets
```

## Dashboards

Grafana auto-provisions (`deploy/monitoring/grafana/provisioning/`):
- **Datasources:** Prometheus + Loki.
- **Dashboard:** *Migration Platform — Overview* (lag, connector health, job metrics).

Add panels by editing `deploy/monitoring/grafana/dashboards/migration-overview.json` (mounted read-only) or via the Grafana UI.

## Logs (Loki)

`promtail` tails container logs (`/var/lib/docker/containers`) and ships them to Loki; query them in Grafana's **Explore** view (e.g. `{container="cdc-platform-backend-1"}`). Set `SPRING_PROFILES_ACTIVE=json` on the backend for structured JSON logs that parse cleanly.

## In-app observability

- **Dashboard** — live pipeline health, lag, job queue.
- **Runs drawer** — per-table phase/status/rows synced (auto-refreshes during a run).
- **Errors/Logs drawer** — failed connector task traces + an automatic remediation hint per error.
- **Alerts** — `alert` records (connector failures, lag breaches, validation drift); acknowledge in the UI; optional webhook via `ALERTS_WEBHOOK_URL`. Lag alerts trigger above `ALERTS_LAG_THRESHOLD`.
- **Audit log** (ADMIN) — every control-plane action.

## Routine operations

| Task | How |
|---|---|
| Start/stop the platform | `docker compose -f deploy/docker-compose.full.yml up -d` / `down` |
| Tail a service | `docker compose -f deploy/docker-compose.full.yml logs -f backend` |
| Pause/resume a migration | Runs drawer → Pause/Resume (pauses connectors, no data loss) |
| Clean re-snapshot | Runs drawer → **Re-run full load** (resets offsets) |
| Scheduled full-load/validation | Schedules drawer (cron); `JobOrchestrator` bounds concurrency (`ORCHESTRATOR_MAX_CONCURRENT`, default 2) |
| Drift detection | Enable scheduled validation; or `RECONCILIATION_CRON` for auto-reconcile projects |

## Scaling notes

- **Control plane** is stateless apart from the metadata DB — run multiple backend replicas behind a load balancer; point them at one external Postgres. Use `PLATFORM_METADATA_EMBEDDED=false`.
- **Data plane** scales with Kafka Connect: add workers to the Connect cluster; increase source `tasks.max` where the engine supports parallel snapshot/streaming; size Kafka partitions/retention for throughput.
- **Single-worker dev** uses replication factor 1 and the rebalance/metadata tunings (see [Configuration](Configuration.md)); production should use a multi-broker Kafka with RF ≥ 3.
- See `docs/HA-TOPOLOGY.md` and `docs/SCALING` notes in the repo for target topologies.

## Backup & disaster recovery

- **Metadata store** — back up the external Postgres (projects, connections, jobs, audit). This is the platform's source of truth; connector configs can be regenerated from it.
- **Connector offsets/state** — held in the Kafka `connect_offsets`/`connect_configs`/`connect_statuses` topics; back these up with your Kafka backup strategy. Losing them forces re-snapshots, not data loss on the target.
- **Recovery** — restore the metadata DB, bring up the stack, and re-run jobs (idempotent upserts) or **Re-run full load** for a clean rebuild. See `docs/BACKUP-DR.md`.

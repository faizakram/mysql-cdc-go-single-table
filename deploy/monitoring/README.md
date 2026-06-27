# Observability stack (#50 / #51)

Metrics, dashboards, and centralized logs for the migration platform.

| Service | Port | Purpose |
|---------|------|---------|
| Prometheus | 9090 | scrapes `platform-backend:8090/actuator/prometheus` + alert rules |
| Grafana | 3001 | provisioned dashboard "Migration Platform — Overview" (admin/admin) |
| Loki | 3100 | log store |
| promtail | — | ships Docker container logs → Loki |

## Run

Bring up the platform + data plane first (so `platform-backend` and the `cdc-dataplane_default`
network exist), then:

```bash
docker compose -f deploy/monitoring/docker-compose.monitoring.yml up -d
open http://localhost:3001      # Grafana, admin/admin
```

The dashboard auto-loads (provisioned). It also opens from the **Grafana ↗** item in the platform's
left nav.

## What the dashboard shows

- **Active jobs / Alerts firing / Connectors up** — at-a-glance stats
- **Sink lag (records) per project** — with a `Project` template variable for drill-down
- **Connector state** timeline (RUNNING vs not), per project/role
- **Jobs by status**, **API request rate & 5xx errors**, **JVM heap**
- **Control-plane logs** panel (Loki) — structured JSON when the backend runs with
  `SPRING_PROFILES_ACTIVE=json`

## Custom metrics (emitted by the backend, #50)

| Metric | Type | Labels |
|--------|------|--------|
| `migration_active_jobs` | gauge | — |
| `migration_jobs` | gauge | `status` |
| `migration_alerts_firing` | gauge | — |
| `migration_sink_lag_records` | gauge | `project` |
| `migration_connector_up` | gauge | `project`, `connector`, `role` |

Plus standard Micrometer series (`jvm_*`, `http_server_requests_*`, `hikaricp_*`).

## Per-table drill-down

Per-project drill-down is built in (the `Project` variable). Per-**table** throughput/row metrics
come from Debezium's own JMX (`debezium_metrics_*`, tagged by table) — enable the JMX exporter on
Kafka Connect (expose `connect:9404`; the `kafka-connect-jmx` scrape job is already configured) to
populate per-table panels. Per-table **reconciliation** (row count + checksum) is always available
in the platform UI's Validation drawer.

## Structured / centralized logging (#50)

Run the backend with `SPRING_PROFILES_ACTIVE=json` to emit one JSON object per log line
(timestamp, level, logger, thread, MDC, stack traces, `service=migration-platform`). promtail ships
all container logs to Loki; query them in Grafana's Explore or the dashboard's logs panel.

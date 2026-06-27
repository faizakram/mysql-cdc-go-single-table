# Monitoring, Logging & Alerting (epic #12)

Three layers, all version-controlled and runnable locally.

## Metrics (#50)

The control plane exports Micrometer metrics at `/actuator/prometheus`. Beyond the stock
`jvm_*` / `http_server_requests_*` / `hikaricp_*` series, it emits business metrics
(`PlatformMetrics`):

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `migration_active_jobs` | gauge | — | jobs RUNNING or SNAPSHOT |
| `migration_jobs` | gauge | `status` | job count per lifecycle state |
| `migration_alerts_firing` | gauge | — | open alerts (should be 0) |
| `migration_sink_lag_records` | gauge | `project` | sink consumer-group lag |
| `migration_connector_up` | gauge | `project`,`connector`,`role` | 1 = RUNNING, 0 = not |

Per-project gauges are refreshed every `METRICS_REFRESH_MS` (default 15s) from live Connect health.
Debezium/Connect per-table JMX metrics can be scraped too — enable the JMX exporter on Connect
(the `kafka-connect-jmx` scrape job is pre-configured).

## Structured logging (#50)

Run the backend with `SPRING_PROFILES_ACTIVE=json` to emit one JSON log object per line
(`@timestamp`, `level`, `logger_name`, `thread_name`, MDC, stack traces, `service=migration-platform`).
promtail ships container logs to Loki; query by field in Grafana. Plain console logging is the
default for local dev.

## Dashboards (#51)

Provisioned Grafana dashboard **"Migration Platform — Overview"** (`deploy/monitoring/`): stat tiles
(active jobs, firing alerts, connectors up), per-project **sink lag**, **connector-state** timeline,
**jobs by status**, **API rate & 5xx errors**, **JVM heap**, and a **Loki logs** panel. A `Project`
template variable drives per-project drill-down. Linked from the platform's left nav (**Grafana ↗**).

```bash
docker compose -f deploy/monitoring/docker-compose.monitoring.yml up -d   # Grafana :3001 (admin/admin)
```

## Alerting (#52)

`AlertMonitor` (scheduled) raises and auto-resolves alerts, deduped one-per-key, with history
retained in the `alert` table:

- **CONNECTOR_FAILED** (CRITICAL) — a connector or task is FAILED.
- **LAG** (WARNING) — sink lag exceeds `ALERTS_LAG_THRESHOLD` (#28; per-deployment, 0 = off).

Alerts route to a Slack/Teams/custom webhook (`ALERTS_WEBHOOK_URL`) or the log. The UI **Alerts**
screen lists them with a firing badge; **acknowledge** silences an alert. Prometheus
`alert-rules.yml` mirrors these at the infra level for Alertmanager-based tooling.

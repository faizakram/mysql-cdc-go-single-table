# Database Migration Platform

A browser-operated, **any-to-any database migration platform** built on Change Data Capture.
Plan, configure, run and validate migrations between heterogeneous databases — SQL Server,
PostgreSQL, MySQL, Oracle, Db2 and MongoDB — from a single console, with live monitoring and
end-to-end validation.

- **Control plane** (`backend`) — Java 21 + Spring Boot 3 REST API: connections, projects,
  job lifecycle, schema discovery, type mapping, validation, planning, auth/RBAC, metrics.
- **UI** (`frontend`) — React 18 + TypeScript + Vite + Ant Design.
- **Data plane** (`debezium-setup/`) — Debezium 2.5 + Kafka Connect (source connectors + JDBC sink)
  with a custom naming/type-conversion SMT baked into the Connect image.
- **Observability** — Prometheus + Grafana + Loki/promtail.

## Quick start (one command)

```bash
docker compose -f deploy/docker-compose.full.yml up -d --build
```

Brings up the whole stack — metadata DB, backend, frontend, Zookeeper, Kafka, Connect, and the
monitoring stack — on one network.

| What | URL | Credentials |
|------|-----|-------------|
| App UI | http://localhost:8081 | `admin` / `admin` |
| Grafana | http://localhost:3001 | `admin` / `admin` |
| Prometheus | http://localhost:9090 | — |
| Kafka Connect REST | http://localhost:8083 | — |

Bring your own source/target databases — anything on your host is reachable from the stack as
`host.docker.internal`. A ready-made MS SQL test source (10 tables, ~10K rows, full type coverage,
CDC enabled) is available under [`debezium-setup/test-data/`](debezium-setup/test-data) via
`bash debezium-setup/test-data/seed.sh`.

Stop everything with:

```bash
docker compose -f deploy/docker-compose.full.yml down
```

## Documentation

Full developer onboarding, run guide, architecture, API reference and operations docs live in the
project **[Wiki](../../wiki)**, with a version-controlled copy under [`docs/wiki/`](docs/wiki/).

## Repository layout

| Path | Purpose |
|------|---------|
| `backend` | Spring Boot control-plane API |
| `frontend` | React UI |
| `deploy` | Compose stacks (`docker-compose.full.yml` is the single-command stack), k8s, monitoring, sql |
| `docs` | ADRs, design notes, architecture review |
| `debezium-setup` | CDC data plane: Connect image, custom SMT, test data |
| `deploy/monitoring` | Prometheus / Grafana / Loki configuration |

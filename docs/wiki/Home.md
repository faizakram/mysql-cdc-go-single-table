# Database Migration Platform

A browser-operated, **any-to-any database migration platform** built on Change Data Capture (CDC). Plan, configure, run, and validate migrations between heterogeneous databases — **SQL Server, PostgreSQL, MySQL, Oracle, Db2, and MongoDB** — from a single console, with live monitoring and end-to-end validation.

It wraps a Debezium + Kafka Connect data plane behind a Spring Boot REST API and a React UI, replacing hand-rolled CLI/curl workflows with a controlled, auditable, observable system.

---

## What it does

| Capability | Summary |
|---|---|
| **Connections** | Register source/target databases; test connectivity; check CDC readiness; discover schema. |
| **Projects** | Link a source + target, pick tables, configure CDC (snapshot mode, delete strategy, naming, type overrides). |
| **Jobs** | Deploy Debezium source + JDBC sink connectors; start/pause/resume/stop; live per-table progress; re-run full load. |
| **Validation** | Row-count & checksum reconciliation, plus a deep integrity report (null PKs, dup keys, missing/extra rows). |
| **Planning** | Dependency-ordered migration plan, dry-run, cost estimate, type recommendations, remediation hints. |
| **Observability** | Live connector/lag health, Prometheus metrics, Grafana dashboards, Loki log aggregation, alerting. |
| **Governance** | JWT auth + RBAC (ADMIN/OPERATOR/VIEWER), AES-256-GCM secret encryption, immutable audit log. |

---

## Architecture at a glance

```
┌──────────────┐   REST    ┌───────────────────────┐   Connect REST   ┌──────────────────────────┐
│  React UI    │ ────────▶ │  Spring Boot control  │ ───────────────▶ │  Kafka Connect (data     │
│  (:8081)     │           │  plane (:8090)        │   (:8083)        │  plane): Debezium source │
└──────────────┘           │  + metadata Postgres  │                  │  + JDBC sink + Kafka     │
                           └───────────┬───────────┘                  └────────────┬─────────────┘
                                       │ JDBC (schema discovery,                    │ CDC stream
                                       │ validation, host.docker.internal)         ▼
                          ┌────────────▼─────────────┐              ┌──────────────────────────┐
                          │  Your SOURCE database    │ ──CDC log──▶ │  Your TARGET database    │
                          └──────────────────────────┘              └──────────────────────────┘
```

The **control plane** never touches the data stream — it generates connector configs, deploys them to Kafka Connect, and observes health. The **data plane** (Debezium → Kafka → JDBC sink) does the actual replication. See **[Architecture](Architecture.md)** for the full picture.

---

## Quick start

```bash
docker compose -f deploy/docker-compose.full.yml up -d --build
```

One command brings up the whole stack (control plane + data plane + monitoring). Then open **http://localhost:8081** (`admin` / `admin`). Full walkthrough in **[Getting Started](Getting-Started.md)**.

| Service | URL | Credentials |
|---|---|---|
| App UI | http://localhost:8081 | `admin` / `admin` |
| Grafana | http://localhost:3001 | `admin` / `admin` |
| Prometheus | http://localhost:9090 | — |
| Kafka Connect REST | http://localhost:8083 | — |
| Swagger / OpenAPI | http://localhost:8090/swagger-ui.html | — |

---

## Where to go next

- **New here?** → [Getting Started](Getting-Started.md)
- **Want to run a real migration?** → [Running a Migration](Running-a-Migration.md)
- **Developing the platform?** → [Local Development](Local-Development.md)
- **Operating it?** → [Monitoring & Operations](Monitoring-and-Operations.md) · [Deployment](Deployment.md) · [Security](Security.md)
- **Hit a problem?** → [Troubleshooting](Troubleshooting.md)

---

## Tech stack

- **Control plane:** Java 21, Spring Boot 3.3, Spring Security + JWT, JPA/Hibernate, Flyway, Micrometer/Prometheus.
- **UI:** React 18, TypeScript, Vite, Ant Design 5, TanStack Query.
- **Data plane:** Debezium 2.5, Kafka Connect (Debezium source connectors + Debezium JDBC sink), custom SMTs.
- **Metadata store:** PostgreSQL (embedded for local dev, external for production).
- **Observability:** Prometheus, Grafana, Loki, promtail.

## Repository layout

| Path | Purpose |
|---|---|
| `backend/` | Spring Boot control-plane API |
| `frontend/` | React UI |
| `deploy/` | Compose stacks (`docker-compose.full.yml`), Kubernetes manifests, monitoring config, SQL helpers |
| `docs/` | ADRs, design notes, architecture review |
| `debezium-setup/` | Data plane: Connect image (`Dockerfile.connect`), custom SMT, test data |

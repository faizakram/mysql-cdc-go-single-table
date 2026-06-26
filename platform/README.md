# Migration Platform (control plane + UI)

Browser-operated control plane for the heterogeneous database migration (CDC) solution. It wraps
the Debezium/Kafka **data plane** (in [`../debezium-setup/`](../debezium-setup/)) behind a REST API
and a React UI, replacing the CLI/curl workflow.

> Tech stack and rationale: [`docs/ADR-0001-tech-stack.md`](docs/ADR-0001-tech-stack.md).
> Backlog: [GitHub Project #22](https://github.com/users/faizakram/projects/22).

## What's in this first (foundation) slice

| Capability | Backlog issue | Status |
|---|---|---|
| Tech-stack decision (ADR) | #18 | ✅ |
| Metadata/state DB + Flyway migrations | #19 | ✅ |
| Spring Boot control-plane skeleton + Kafka Connect proxy | #21, #45 | ✅ |
| Project & Connection CRUD | #22 | ✅ |
| Connection **test** + AES-GCM secret encryption | #22, #43 (seed) | ✅ |
| OpenAPI / Swagger UI | #24 | ✅ partial |
| Job lifecycle endpoints (start/pause/resume/stop) | #23 | ✅ skeleton |
| React + Ant Design UI shell, Dashboard, Projects, Connections | #34, #35 | ✅ |

Deferred to their own issues (clearly marked `TODO(#nn)` in code): connector-config generation
from project settings (#25), schema discovery (#30), real-time monitoring/metrics (#50), auth (#55).

## Layout

```
platform/
├── backend/     Java 21 + Spring Boot 3 control-plane API
├── frontend/    React 18 + TS + Vite + Ant Design SPA
├── deploy/      docker-compose for the platform tier
└── docs/        ADRs
```

## Run locally

### Backend
Requires a Postgres metadata DB. Quickest path is the compose file below; or point it at any Postgres:

```bash
cd backend
METADATA_DB_URL=jdbc:postgresql://localhost:5433/migration_platform \
KAFKA_CONNECT_URL=http://localhost:8083 \
mvn spring-boot:run
```

- API base: `http://localhost:8090/api/v1`
- Swagger UI: `http://localhost:8090/swagger-ui.html`
- Health/metrics: `http://localhost:8090/actuator/health`, `/actuator/prometheus`

### Frontend
```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 (proxies /api → :8090)
```

### Everything via Docker
```bash
cd deploy
docker compose -f docker-compose.platform.yml up --build
# UI:      http://localhost:8081
# API:     http://localhost:8090
# metadata Postgres: localhost:5433
```
Set `KAFKA_CONNECT_URL` to your running Kafka Connect (the `debezium-setup` stack on `:8083`).

## API surface (v1)

| Method | Path | Purpose |
|---|---|---|
| `GET/POST/PUT/DELETE` | `/api/v1/connections` | Connection CRUD (passwords write-only) |
| `POST` | `/api/v1/connections/test` | Test ad-hoc params before saving |
| `POST` | `/api/v1/connections/{id}/test` | Test a saved connection |
| `GET/POST/PUT/DELETE` | `/api/v1/projects` | Project CRUD |
| `GET/POST` | `/api/v1/projects/{id}/jobs` | List / create jobs |
| `POST` | `/api/v1/jobs/{id}/{start\|pause\|resume\|stop}` | Job lifecycle |
| `GET` | `/api/v1/connect/connectors` … | Kafka Connect proxy |

## Security note
- **Secrets:** connection passwords are encrypted at rest (AES-256-GCM) and never returned by the API.
  The dev crypto key **must** be overridden via `PLATFORM_CRYPTO_KEY` in any real environment, and
  moved to a secrets manager per issue #43.
- **TLS (#44):** connection encryption is **secure by default** — SQL Server uses `encrypt=true`
  unless explicitly disabled per connection (TLS section on the form / `options.encrypt`,
  `options.trustServerCertificate`); PostgreSQL honours `options.sslmode`. These flow into both the
  JDBC test/discovery paths and the generated Debezium connector configs. Enable HTTPS for the API
  with `SERVER_SSL_ENABLED=true` + a keystore (or terminate TLS at an ingress).
- **Kafka Connect (#45):** all connector operations go through the control plane; Connect must not be
  exposed publicly. Set `KAFKA_CONNECT_USER`/`KAFKA_CONNECT_PASSWORD` to authenticate to a secured
  Connect REST endpoint (basic auth).

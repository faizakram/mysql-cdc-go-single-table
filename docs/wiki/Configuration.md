# Configuration

All backend configuration is bound from environment variables (overriding `application.yml`) and the `@ConfigurationProperties` tree (`platform.*`). This page lists every knob, its default, and its purpose.

> The `docker-compose.full.yml` stack sets sensible in-network defaults. Override secrets and DB settings via environment variables (or a `.env` file next to the compose file) for any real deployment.

## Server & TLS

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `server.port` | `8090` | HTTP listen port. |
| `SERVER_SSL_ENABLED` | `server.ssl.enabled` | `false` | Enable TLS on the API. |
| `SERVER_SSL_KEYSTORE` | `server.ssl.key-store` | — | Path to PKCS12 keystore. |
| `SERVER_SSL_KEYSTORE_PASSWORD` | `server.ssl.key-store-password` | — | Keystore password. |
| `SERVER_SSL_KEYSTORE_TYPE` | `server.ssl.key-store-type` | `PKCS12` | Keystore type. |

## Metadata store

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `PLATFORM_METADATA_EMBEDDED` | `platform.metadata.embedded` | `true` | Run an in-process Postgres (dev). Set `false` to use an external DB (production). |
| `PLATFORM_METADATA_DATA_DIR` | `platform.metadata.embedded-data-dir` | `${user.home}/.migration-platform/pgdata` | Embedded Postgres data dir. |
| `METADATA_DB_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/migration_platform` | JDBC URL (external mode). |
| `METADATA_DB_USER` | `spring.datasource.username` | `postgres` | DB user. |
| `METADATA_DB_PASSWORD` | `spring.datasource.password` | `postgres` | DB password. |

Flyway runs migrations on boot; Hibernate `ddl-auto=validate` (never creates/alters schema). See [Database Schema](Database-Schema.md).

## Kafka Connect / data plane

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `KAFKA_CONNECT_URL` | `platform.connect.base-url` | `http://localhost:8083` | Connect REST endpoint (compose sets `http://connect:8083`). |
| `KAFKA_BOOTSTRAP_SERVERS` | `platform.connect.kafka-bootstrap` | `kafka:9092` | Kafka bootstrap **as seen from Connect** (used in generated source schema-history config and for lag lookups). |
| `KAFKA_CONNECT_USER` | `platform.connect.username` | — | Optional Basic-auth for a secured Connect REST. |
| `KAFKA_CONNECT_PASSWORD` | `platform.connect.password` | — | Optional Basic-auth password. |

Data-plane (Connect container) env in compose: `BOOTSTRAP_SERVERS`, `GROUP_ID`, `CONFIG/OFFSET/STATUS_STORAGE_TOPIC` (+ replication factor `1`), and two latency tunings:

| Env var | Value | Purpose |
|---|---|---|
| `CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS` | `0` | Assign new connector tasks immediately (cuts ~5-min cold start in single-worker dev). |
| `CONNECT_CONSUMER_METADATA_MAX_AGE_MS` | `5000` | Sink consumer discovers new source topics within ~5s. |

## Connector secrets

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `CONNECT_SECRET_MODE` | `platform.connect.secrets.mode` | `inline` | How DB passwords reach connector configs: `inline` (plaintext in config), `file` (`${file:…}` provider), `env` (`${env:…}` provider). |
| `CONNECT_SECRET_FILE_PATH` | `platform.connect.secrets.file-path` | `/opt/connect-secrets` | Base path for mounted secret files (`file` mode). |
| `CONNECT_SECRET_ENV_PREFIX` | `platform.connect.secrets.env-prefix` | `CDC_SECRET_` | Env-var prefix (`env` mode). |

For `file`/`env` modes, Kafka Connect must declare the matching `config.providers`. See [Security](Security.md#connector-secret-providers).

## Crypto & auth

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `PLATFORM_CRYPTO_KEY` | `platform.crypto.key` | dev key (base64) | **AES-256** key (base64 of 32 bytes) encrypting connection passwords at rest. **Override in production.** |
| `JWT_SECRET` | `platform.auth.jwt-secret` | dev secret | HMAC-SHA256 JWT signing key (≥ 32 bytes). **Override in production.** |
| `JWT_TTL_MINUTES` | `platform.auth.ttl-minutes` | `480` | Token lifetime (8h). |
| `ADMIN_USERNAME` | `platform.auth.admin-username` | `admin` | Bootstrap admin (created only if no users exist). |
| `ADMIN_PASSWORD` | `platform.auth.admin-password` | `admin` | Bootstrap admin password. **Change immediately.** |
| `CORS_ALLOWED_ORIGINS` | `platform.cors.allowed-origins` | `http://localhost:5173` | Allowed CORS origins (compose sets `http://localhost:8081`). |

## Scheduling, alerts, metrics, audit

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `RECONCILIATION_CRON` | `platform.reconciliation.cron` | `0 0 */6 * * *` | Drift-detection schedule (6-field cron); runs only for projects with `autoReconcile=true`. |
| `ALERTS_WEBHOOK_URL` | `platform.alerts.webhook-url` | — | Slack/Teams/webhook for alerts; empty = log only. |
| `ALERTS_MONITOR_CRON` | `platform.alerts.monitor-cron` | `0 */2 * * * *` | Connector-health check cadence. |
| `ALERTS_LAG_THRESHOLD` | `platform.alerts.lag-threshold` | `0` | Raise a LAG alert above this many records; `0` disables. |
| `METRICS_REFRESH_MS` | `platform.metrics.refresh-ms` | `15000` | Per-project Prometheus gauge refresh interval. |
| `ORCHESTRATOR_MAX_CONCURRENT` | `platform.orchestrator.max-concurrent` | `2` | Max concurrent orchestrated tasks; excess queue. |
| `SCHEDULER_SWEEP_CRON` | `platform.scheduler.sweep-cron` | `*/30 * * * * *` | How often to check for due schedules. |
| `AUDIT_RETENTION_DAYS` | `platform.audit.retention-days` | `90` | Audit-log retention window. |
| `PLATFORM_AUDIT_RETENTION_CRON` | `platform.audit.retention-cron` | `0 30 3 * * *` | Daily audit retention sweep. |

> Progress polling interval: `platform.progress.interval-ms` (`15000`) controls how often `ProgressTracker` refreshes per-table progress.

## Logging & actuator

| Setting | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | (none) | Set `json` for structured JSON logs (Loki/ELK); empty = human-readable console. |
| `logging.level.com.migration.platform` | `INFO` | App log level. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Exposed actuator endpoints. |
| `management.endpoint.health.probes.enabled` | `true` | K8s liveness/readiness probes at `/actuator/health/{liveness,readiness}`. |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI. |

## Production override example

```bash
# .env beside deploy/docker-compose.full.yml
PLATFORM_METADATA_EMBEDDED=false
METADATA_DB_URL=jdbc:postgresql://prod-pg:5432/migration_platform
METADATA_DB_USER=platform
METADATA_DB_PASSWORD=<secret>

PLATFORM_CRYPTO_KEY=<base64 32-byte key>
JWT_SECRET=<random 32+ byte string>
ADMIN_USERNAME=ops_admin
ADMIN_PASSWORD=<secret>
CORS_ALLOWED_ORIGINS=https://migrate.example.com

CONNECT_SECRET_MODE=file
SERVER_SSL_ENABLED=true
SERVER_SSL_KEYSTORE=/etc/ssl/keystore.p12
SERVER_SSL_KEYSTORE_PASSWORD=<secret>
SPRING_PROFILES_ACTIVE=json
```

Generate a crypto key: `openssl rand -base64 32`.

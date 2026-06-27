# Deployment

## Topologies

| Environment | Recommended setup |
|---|---|
| **Local / demo** | `deploy/docker-compose.full.yml` — everything on one network, embedded-free metadata Postgres container. |
| **Single host / staging** | Same compose, but override secrets, use a persistent external Postgres, and front the UI/API with TLS. |
| **Production** | Backend replicas + external managed Postgres; a real Kafka + Kafka Connect cluster; Kubernetes (`deploy/k8s/`). |

## One-command stack (compose)

`deploy/docker-compose.full.yml` brings up: `metadata-db`, `backend`, `frontend`, `zookeeper`, `kafka`, `connect`, `prometheus`, `grafana`, `loki`, `promtail`.

```bash
docker compose -f deploy/docker-compose.full.yml up -d --build   # start
docker compose -f deploy/docker-compose.full.yml ps              # status
docker compose -f deploy/docker-compose.full.yml down            # stop (keep data)
docker compose -f deploy/docker-compose.full.yml down -v         # stop + wipe metadata volume
```

Key wiring:
- Backend → Connect in-network: `KAFKA_CONNECT_URL=http://connect:8083` (no `host.docker.internal` between platform components).
- Backend & Connect both keep `host.docker.internal:host-gateway` so they can reach **your** databases on the host.
- The Connect image is built from `debezium-setup/Dockerfile.connect` with the naming SMT baked in (override with `CONNECT_IMAGE`).
- Metadata persists in the `platform-metadata` named volume.

Provide production values via a `.env` file beside the compose file (see [Configuration](Configuration.md#production-override-example)).

## Images

| Image | Built from | Notes |
|---|---|---|
| `backend` | `backend/Dockerfile` | Multi-stage Maven → `eclipse-temurin:21-jre`. |
| `frontend` | `frontend/Dockerfile` | Multi-stage `npm ci && build` → `nginx:alpine`; nginx proxies `/api` → `backend:8090`. |
| `connect` | `debezium-setup/Dockerfile.connect` | Debezium Connect 2.5 + custom SMT jar; CI publishes to GHCR. |

## Kubernetes (`deploy/k8s/`)

Manifests for a control-plane deployment:
- `00-namespace.yaml`
- `10-platform-config.yaml` — ConfigMap/Secret for env (DB URL, JWT/crypto keys, Connect URL).
- `20-platform-backend.yaml` — backend Deployment + Service; liveness/readiness via `/actuator/health/{liveness,readiness}` (enabled by default).
- `30-kafka-connect-strimzi.yaml` — Kafka Connect via the Strimzi operator (recommended way to run Connect on K8s).

Production guidance:
- Use an **external managed Postgres** (`PLATFORM_METADATA_EMBEDDED=false`); never the embedded DB for multi-replica.
- Store `JWT_SECRET`, `PLATFORM_CRYPTO_KEY`, and DB credentials in K8s **Secrets**; use `CONNECT_SECRET_MODE=file`/`env` so DB passwords resolve from mounted secrets, not the Connect config topic.
- Front the UI/API with an Ingress doing TLS; restrict Connect's REST to the cluster network.
- Scale the backend horizontally (stateless); scale Connect with the Strimzi `KafkaConnect` replicas.

## CI/CD

GitHub Actions:
- **Platform CI** (`.github/workflows/platform-ci.yml`) — backend `mvn verify` (unit + Testcontainers integration) and frontend type-check, build, Vitest, and Playwright smoke. Triggered on changes under `backend/**`, `frontend/**`, `deploy/**`.
- **SMT & security** (`.github/workflows/smt-and-security.yml`) — builds/publishes the custom Connect image and runs a Trivy filesystem scan.

## Health & readiness

- Liveness: `GET /actuator/health/liveness` (process up).
- Readiness: `GET /actuator/health/readiness` (includes metadata DB connectivity).
- General: `GET /actuator/health` (public).

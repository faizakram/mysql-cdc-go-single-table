# Getting Started

This page takes a developer from a fresh clone to a running platform in a few minutes. No prior knowledge of the codebase is assumed.

## 1. Prerequisites

You only need **Docker** for the one-command path:

- **Docker Desktop** (macOS/Windows) or Docker Engine + Compose v2 (Linux). Compose v2 means the `docker compose` (space, not hyphen) command.
- ~4 GB free RAM for the full stack (Kafka + Connect + Postgres + monitoring).
- Ports free on the host: **8081, 8090, 8083, 9090, 3001, 3100, 5433** (see the table below).

> On Apple Silicon (arm64), the platform images run natively. Source/target database images you bring (e.g. SQL Server) may run under emulation.

For running the backend/frontend **outside** Docker (contributing code), you'll also need Java 21 + Maven and Node 22 — see **[Local Development](Local-Development.md)**.

## 2. Clone

```bash
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table
```

## 3. Start everything (one command)

```bash
./deploy/up.sh
```

This builds and starts the stack, waits until it's healthy, and prints a console banner listing every
URL, credential and endpoint. (Plain `docker compose -f deploy/docker-compose.full.yml up -d --build`
works too — the wrapper just adds the readiness wait and the pretty summary.)

It starts **ten** services on a single Docker network:

| Service | Role | Host URL / port |
|---|---|---|
| `frontend` | React UI (nginx) | http://localhost:8081 |
| `backend` | Control-plane API | http://localhost:8090 |
| `connect` | Debezium Kafka Connect | http://localhost:8083 |
| `kafka` | Message bus | internal |
| `zookeeper` | Kafka coordination | internal |
| `metadata-db` | Platform Postgres | localhost:5433 |
| `prometheus` | Metrics | http://localhost:9090 |
| `grafana` | Dashboards | http://localhost:3001 |
| `loki` | Log store | internal:3100 |
| `promtail` | Log shipper | internal |

First build takes a few minutes (Maven + npm + image pulls). Check progress:

```bash
docker compose -f deploy/docker-compose.full.yml ps
docker compose -f deploy/docker-compose.full.yml logs -f backend
```

The backend is ready when `http://localhost:8090/actuator/health` returns `{"status":"UP"}`.

## 4. Log in

Open **http://localhost:8081** and sign in with the bootstrap admin:

- **Username:** `admin`
- **Password:** `admin`

> These defaults come from `ADMIN_USERNAME` / `ADMIN_PASSWORD`. Change them in any real environment — see [Security](Security.md).

## 5. (Optional) Spin up a test source

The repo ships a ready-made MS SQL Server source with a rich schema (10 tables, ~10,000 rows, full data-type coverage, CDC enabled) so you can exercise a migration immediately:

```bash
bash debezium-setup/test-data/seed.sh
```

This starts a SQL Server container on **localhost:1433** with database `TestShop` (`sa` / `Str0ngP@ssw0rd!`) and enables CDC on all tables. See [Running a Migration](Running-a-Migration.md) for how to wire it up.

## 6. Connecting your own databases — the one rule

The platform runs in containers, so **`localhost` from inside a container is the container itself, not your machine.** To reach a database running on your **host** (your Mac/PC, or another container that publishes a host port), use:

```
host = host.docker.internal
```

For example, a Postgres on your host's `:5432` is reached by the platform as `host.docker.internal:5432`. This applies to both the backend (connection tests/schema discovery) and Kafka Connect (the actual CDC). See [Troubleshooting](Troubleshooting.md#cant-connect-to-my-database) if a connection test fails.

## 7. Stop / reset

```bash
# Stop (keeps the metadata volume — your projects/connections persist)
docker compose -f deploy/docker-compose.full.yml down

# Full reset (also wipes the metadata volume)
docker compose -f deploy/docker-compose.full.yml down -v
```

## Next steps

- **[Running a Migration](Running-a-Migration.md)** — connect databases, pick tables, run CDC, validate.
- **[Configuration](Configuration.md)** — every environment variable and tuning knob.
- **[Local Development](Local-Development.md)** — run the backend/frontend from source.

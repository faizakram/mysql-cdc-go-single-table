# Troubleshooting

Common failures and how to fix them. Most issues fall into three buckets: **networking** (containers can't reach your DB), **target schema**, and **data-plane not running**.

## Can't connect to my database

**Symptom:** Connection test fails, or the connector can't reach the source/target.

**Cause:** The platform runs in containers. `localhost`/`127.0.0.1` inside a container is the *container itself*, not your machine.

**Fix:** For any database on your **host** (or another container publishing a host port), set the connection **Host** to `host.docker.internal` (keep the published port, e.g. `5432`, `1433`). This applies to both connection tests (backend) and the actual CDC (Kafka Connect) — both containers are configured with `host.docker.internal`.

```bash
# Verify the backend can reach your DB host/port:
docker exec cdc-platform-backend-1 bash -c \
  'timeout 3 bash -c "exec 3<>/dev/tcp/host.docker.internal/5432" && echo REACHABLE || echo NO'
```

## Sink fails: `schema "X" does not exist`

**Symptom:** Source connector is RUNNING but the **sink** task FAILED; trace shows `CREATE TABLE "X"."..."` → `ERROR: schema "X" does not exist`.

**Cause:** The Debezium JDBC sink auto-creates **tables**, but not **schemas**. Your project's *target schema* doesn't exist in the target database.

**Fix:** Either pre-create the schema on the target, or set the project's target schema to one that exists (e.g. `public`):
```sql
CREATE SCHEMA IF NOT EXISTS myschema;
```
Then restart the sink (Runs → it will retry) or re-run the job.

## Starting a job fails: `I/O error POST /connectors`

**Symptom:** Job goes `FAILED` immediately with `I/O error on POST … http://connect:8083/connectors`.

**Cause:** Kafka Connect (the data plane) isn't running/reachable.

**Fix:** With `deploy/docker-compose.full.yml` the data plane is always part of the stack — make sure it's up:
```bash
docker compose -f deploy/docker-compose.full.yml ps connect
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8083/   # expect 200
```
If you run components separately, start Kafka + Connect before starting a job, and ensure `KAFKA_CONNECT_URL` points at it.

## CDC readiness fails / no changes captured

- **SQL Server:** SQL **Agent** must be running and CDC enabled at DB and table level. The seed script (`debezium-setup/test-data/seed.sh`) does this for the demo DB. Don't select `systranschemas` or other system tables — they have no CDC capture instance and will fail the source connector.
- **PostgreSQL:** `wal_level=logical`, a role with `REPLICATION`, and slot/publication available.
- **MySQL:** binlog on (`log_bin`, `binlog_format=ROW`, `binlog_row_image=FULL`); use the MySQL 8.0 line.
- Use the connection's **CDC readiness** check — it names the exact missing prerequisite.

## Snapshot finished but job stays in SNAPSHOT

`ProgressTracker` flips the job to RUNNING when it detects the snapshot is complete from the source connector's committed offsets. If it lingers: check the source connector is healthy (`GET /connectors/<name>/status`) and that the Connect build exposes the offsets endpoint (Kafka Connect 3.6+). The bundled image does.

## Exotic column types look wrong on the target

Types like SQL Server `geography`, `hierarchyid`, `sql_variant` are flagged in the **Mapping** drawer as lossy/special. Debezium serializes them as bytes/strings; verify the target representation after the first load and apply a **type override** or post-processing if needed.

## Sink takes minutes to start writing

A fresh sink consumer can be slow to join/discover topics. The bundled Connect image sets `CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS=0` and `CONNECT_CONSUMER_METADATA_MAX_AGE_MS=5000` to cut this from ~5 min to seconds. If you run your own Connect, set the same.

## Login works in the UI but API calls 401

The UI attaches `Authorization: Bearer <token>` automatically; a 401 means the token expired or `JWT_SECRET` changed (invalidating existing tokens). Log in again. If you rotated `JWT_SECRET`, all existing tokens are invalidated by design.

## Useful commands

```bash
# Service status / logs
docker compose -f deploy/docker-compose.full.yml ps
docker compose -f deploy/docker-compose.full.yml logs -f backend connect

# Connector states
curl -s http://localhost:8083/connectors | jq .
curl -s http://localhost:8083/connectors/<name>/status | jq .

# Backend health & a metric
curl -s http://localhost:8090/actuator/health
curl -s 'http://localhost:9090/api/v1/query?query=up{job="migration-platform"}'

# Reset everything (wipes metadata)
docker compose -f deploy/docker-compose.full.yml down -v
```

Still stuck? Check the **Errors/Logs** drawer on the run (it shows the failed task trace + a remediation hint), and the Grafana/Loki logs.

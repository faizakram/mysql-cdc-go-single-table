# Disaster Recovery

What state exists, how it's backed up, how to restore it, and the RTO/RPO targets. HA (see [Architecture → Target architecture](Architecture.md#target--production-architecture-ha)) prevents most incidents; this is for when recovery is needed.

## What state exists, and where

| State | Lives in | Backup method | Loss impact |
|---|---|---|---|
| Platform metadata (projects, connections, jobs, reconciliation, users, alerts) | Metadata Postgres | Continuous WAL archiving + nightly base backup (PITR) | Lose project/config history; the data plane keeps running. |
| Connection secrets | Metadata Postgres, **AES-256-GCM encrypted** with `PLATFORM_CRYPTO_KEY` | Backed up with the DB **and** the key stored separately in a secrets manager | Without the key, encrypted secrets are unrecoverable — back the key up independently. |
| Connector configs & **CDC offsets** | Kafka `connect_configs` / `connect_offsets` / `connect_statuses` (RF=3) | Replicated by Kafka; optionally MirrorMaker 2 to a DR cluster | Lose offsets → connectors re-snapshot from scratch. |
| Source CDC capture | Source DB change tables / logs (retention-bounded) | Customer DB backups | If offsets are lost **and** CDC retention expired, a fresh snapshot is required. |
| Target data | Target database | Customer DB backups | Re-derivable by re-running the migration if the source is intact. |

## RPO / RTO targets (recommended)

| Component | RPO | RTO |
|---|---|---|
| Metadata store | ≤ 5 min (WAL archiving) | ≤ 30 min (restore + PITR) |
| Kafka / Connect (offsets) | 0 with RF=3 in-cluster | minutes (rebalance) |
| Full pipeline rebuild (worst case) | n/a | hours (re-snapshot large tables) |

## Backups

**Metadata Postgres (PITR).** Use the managed service's PITR or `pgBackRest` / CloudNativePG. A logical safety net in addition to WAL/PITR:
```bash
pg_dump -Fc -h "$METADATA_HOST" -U "$METADATA_USER" migration_platform > metadata-$(date +%F).dump
```
Back up `PLATFORM_CRYPTO_KEY` and `JWT_SECRET` in your **secrets manager** — never in the DB dump.

**Kafka Connect state.** With RF=3 the internal topics are HA in-cluster. For cross-region DR, replicate them with MirrorMaker 2 to a standby cluster.

## Restore procedures

### A. Control plane lost (pods/cluster), metadata intact
Redeploy the backend (`deploy/k8s/20-platform-backend.yaml`) pointed at the existing metadata DB. It's stateless — it reconnects and resumes managing the live connectors. No data-plane impact.

### B. Metadata store lost
1. Restore Postgres to the latest PITR point.
2. Ensure `PLATFORM_CRYPTO_KEY` matches the key in force when secrets were written — otherwise encrypted connection passwords won't decrypt (rotate / re-enter them if the key was lost).
3. Restart the backend; verify projects/connections load and `/actuator/health` is UP.

### C. Connect worker or Kafka broker lost
No action required — the Connect cluster rebalances connectors to surviving workers; they resume from committed offsets. Confirm via the dashboard (connector state RUNNING, lag draining).

### D. Connector offsets lost (worst case)
1. Recreate connectors from the platform (configs regenerate from the project config).
2. If source CDC retention still covers the gap, streaming resumes from the earliest available position (LSN/binlog/SCN).
3. If not, run a fresh snapshot (`snapshotMode=initial`) — the JDBC sink upserts by primary key, so re-snapshotting is **idempotent** and converges the target without duplicates. (This is what the **Re-run full load** action does.)
4. Run reconciliation to confirm row-count + checksum parity before declaring recovery.

## Verification after any restore

1. `/actuator/health` UP on all backend replicas.
2. All connectors RUNNING (dashboard / monitoring).
3. Sink lag draining toward zero; no LAG alert.
4. Reconciliation COUNT + CHECKSUM = MATCH for each project (soft-delete aware) — see [Running a Migration → Validate](Running-a-Migration.md#step-7--validate).

## DR drills

Exercise **restore B** (metadata PITR) and **restore D** (offset loss → idempotent re-snapshot + reconcile) on a schedule. Recovery you haven't tested is a plan, not a capability.

See also: [Monitoring & Operations](Monitoring-and-Operations.md), [Scaling & Capacity Planning](Scaling-and-Capacity-Planning.md), and the `docs/BACKUP-DR.md` / `docs/HA-TOPOLOGY.md` deep-dives.

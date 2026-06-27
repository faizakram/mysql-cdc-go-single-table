# Backup, Restore & Disaster Recovery Runbook (#65)

What to back up, how to restore it, and how to recover the pipeline after a failure. Pairs with
[HA-TOPOLOGY.md](HA-TOPOLOGY.md) (HA prevents most incidents; this covers when recovery is needed).

## What state exists, and where

| State | Lives in | Backup method | Loss impact |
|-------|----------|---------------|-------------|
| Platform metadata (projects, connections, jobs, reconciliation, users, alerts) | Metadata Postgres | Continuous WAL archiving + nightly base backup (PITR) | Lose project/config history; data-plane keeps running |
| Connection secrets | Metadata Postgres, **encrypted (AES-256-GCM)** with `PLATFORM_CRYPTO_KEY` | Backed up with the DB **and** the key backed up separately in a secrets manager | Without the key, encrypted secrets are unrecoverable — back the key up independently |
| Connector configs & **CDC offsets** | Kafka `connect_configs` / `connect_offsets` / `connect_statuses` topics (RF=3) | Replicated by Kafka; optionally MirrorMaker to a DR cluster | Lose offsets → connectors re-snapshot from scratch |
| Source DB CDC capture | SQL Server CDC change tables (retention-bounded) | Customer DB backups | If offsets are lost **and** CDC retention has expired, a fresh snapshot is required |
| Target data | Target Postgres | Customer DB backups | Re-derivable by re-running the migration if source intact |

## RPO / RTO targets (recommended)

| Component | RPO | RTO |
|-----------|-----|-----|
| Metadata store | ≤ 5 min (WAL archiving) | ≤ 30 min (restore + PITR) |
| Kafka/Connect (offsets) | 0 with RF=3 in-cluster | minutes (rebalance) |
| Full pipeline rebuild (worst case) | n/a | hours (re-snapshot large tables) |

## Backups

**Metadata Postgres (PITR).** Use the managed service's PITR or `pgBackRest`/CloudNativePG:
```bash
# logical safety net (in addition to WAL/PITR)
pg_dump -Fc -h $METADATA_HOST -U $METADATA_USER migration_platform > metadata-$(date +%F).dump
```
Back up `PLATFORM_CRYPTO_KEY` and `JWT_SECRET` in your secrets manager — **not** in the DB dump.

**Kafka Connect state.** With RF=3 the internal topics are HA in-cluster. For cross-region DR,
replicate them with MirrorMaker 2 to a standby cluster.

## Restore procedures

### A. Control plane lost (pods/cluster), metadata intact
Redeploy the backend (`deploy/k8s/20-platform-backend.yaml`) pointed at the existing metadata DB.
Stateless — it reconnects and resumes managing the live connectors. No data-plane impact.

### B. Metadata store lost
1. Restore Postgres to the latest PITR point.
2. Ensure `PLATFORM_CRYPTO_KEY` matches the one in force when secrets were written (else encrypted
   connection passwords won't decrypt — rotate/re-enter them if the key was lost).
3. Restart the backend; verify projects/connections load and `/actuator/health` is UP.

### C. Connect worker or Kafka broker lost
No action — Strimzi rebalances connectors to surviving workers; they resume from committed offsets.
Confirm via the dashboard (connector state RUNNING, lag #50 draining).

### D. Connector offsets lost (worst case)
1. Recreate connectors from the platform (configs regenerate from project config).
2. If source CDC retention still covers the gap, streaming resumes from the earliest available LSN.
3. If not, run a fresh snapshot (`snapshot.mode=initial`) — the JDBC sink upserts by primary key,
   so re-snapshotting is **idempotent** and converges the target without duplicates.
4. Run reconciliation (#49) to confirm row-count + checksum parity before declaring recovery.

## Verification after any restore

1. `/actuator/health` UP on all backend replicas.
2. All connectors RUNNING (dashboard / monitoring #50).
3. Sink lag (#50) draining toward zero; no LAG alert (#28).
4. Reconciliation COUNT + CHECKSUM = MATCH for each project (soft-delete aware).

## DR drills

Exercise restore B (metadata PITR) and restore D (offset loss → idempotent re-snapshot + reconcile)
on a schedule. Recovery you haven't tested is a plan, not a capability.

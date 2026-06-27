# High-Availability Topology (#64)

How to run the migration platform with no single point of failure. Manifests live in
[`deploy/k8s/`](../../deploy/k8s); apply them in filename order after installing the Strimzi operator.

## Components & failure domains

| Layer | HA strategy | Survives |
|-------|-------------|----------|
| **Control plane** (Java backend) | Stateless; `Deployment` with 2+ replicas, HPA (2→6 on CPU), PodDisruptionBudget, topology spread across nodes | pod/node loss, rolling upgrades |
| **Metadata store** (Postgres) | External HA Postgres — CloudNativePG / Patroni / RDS Multi-AZ with a `-rw` (primary) and `-ro` (replica) service | primary failover (RPO≈0 with sync replication) |
| **Kafka** | Strimzi 3-broker quorum, RF=3, `min.insync.replicas=2` | one broker loss with no data loss |
| **Kafka Connect** | Strimzi distributed cluster, 2+ workers, replicated config/offset/status topics (RF=3) | worker loss → connectors rebalance automatically |
| **Source / target DBs** | Customer-managed HA (Always On AG / Multi-AZ) | DB failover; CDC resumes from the last committed offset |

## Why the control plane scales safely

All control-plane state is in the metadata Postgres — the pods hold none, so any replica can serve
any request. The two background jobs (reconciliation #49, alert monitor #52) are the only
multi-instance concern; run them under leader election / the job-queue guard (#54) so they fire
once per cluster, not once per pod. Until that lands, scale the API replicas freely but keep
scheduled jobs pinned to a single leader.

## Connect cluster = the real availability win

The single-worker data plane in `docker-compose.dataplane.yml` is for local runs only. In a Strimzi
`KafkaConnect` cluster, connector configuration and **source offsets** live in replicated Kafka
topics, not on a worker's disk. If a worker dies, the cluster rebalances the connector to a
surviving worker and it resumes from the last committed offset — no snapshot re-run, no data loss.
Scale throughput by raising `tasks.max` and adding workers.

## Apply order

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl create -f 'https://strimzi.io/install/latest?namespace=cdc-platform' -n cdc-platform
kubectl apply -f deploy/k8s/30-kafka-connect-strimzi.yaml   # Kafka + Connect (operator reconciles)
# provision the external metadata Postgres (CloudNativePG/RDS) and point 10-platform-config at it
kubectl apply -f deploy/k8s/10-platform-config.yaml         # populate the Secret from your secrets manager first
kubectl apply -f deploy/k8s/20-platform-backend.yaml
```

## Scaling & capacity notes

- **Snapshot throughput**: raise per-project `snapshotMaxThreads` / `snapshotFetchSize` (#27) for
  large initial loads.
- **Streaming throughput**: raise sink `tasks.max` and add Connect workers; watch lag (#50) and the
  lag alert (#28) as the backpressure signal.
- **Control-plane load** is light (config + monitoring proxy); the HPA on CPU is usually idle. The
  metadata Postgres is the component to size for connection count and reconciliation query load.

See also: [BACKUP-DR.md](BACKUP-DR.md) for recovery procedures, [CDC-HARDENING.md](CDC-HARDENING.md)
for the per-project tuning knobs.

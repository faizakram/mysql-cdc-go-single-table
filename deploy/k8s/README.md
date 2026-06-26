# Kubernetes deployment (HA) — #64

Production HA topology for the CDC migration platform. Full rationale, failure domains, and
scaling notes are in [platform/docs/HA-TOPOLOGY.md](../../platform/docs/HA-TOPOLOGY.md); recovery
procedures are in [platform/docs/BACKUP-DR.md](../../platform/docs/BACKUP-DR.md).

| File | Purpose |
|------|---------|
| `00-namespace.yaml` | `cdc-platform` namespace |
| `10-platform-config.yaml` | Control-plane ConfigMap + Secret **template** (populate from a secrets manager) |
| `20-platform-backend.yaml` | Stateless backend `Deployment` (2 replicas) + Service + HPA + PodDisruptionBudget |
| `30-kafka-connect-strimzi.yaml` | Strimzi `Kafka` (3 brokers) + `KafkaConnect` (2 workers) with the Debezium connectors + custom SMT |

## Quick start

```bash
kubectl apply -f 00-namespace.yaml
kubectl create -f 'https://strimzi.io/install/latest?namespace=cdc-platform' -n cdc-platform
kubectl apply -f 30-kafka-connect-strimzi.yaml
# Provision an external HA Postgres for metadata and point 10-platform-config.yaml at it.
# Fill platform-secrets from your secrets manager (NEVER commit real values).
kubectl apply -f 10-platform-config.yaml
kubectl apply -f 20-platform-backend.yaml
```

These manifests are a production-ready **starting point**, not a turnkey install: set real image
tags, an Ingress/TLS in front of the backend Service, and externalize secrets (#43) before going
live.

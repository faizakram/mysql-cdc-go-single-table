# Documentation

The authoritative documentation lives in **[`docs/wiki/`](wiki/)** — the single source of truth,
auto-published to the **[GitHub Wiki](../../wiki)** on every merge to `main`
(see [`.github/workflows/wiki-sync.yml`](../.github/workflows/wiki-sync.yml)).

Start at **[`docs/wiki/README.md`](wiki/README.md)** or the [Wiki home](../../wiki).

## Wiki pages

| | |
|---|---|
| [Getting Started](wiki/Getting-Started.md) | prerequisites, one-command run, first login |
| [Running a Migration](wiki/Running-a-Migration.md) | end-to-end runbook + CDC prerequisites |
| [Local Development](wiki/Local-Development.md) | run backend/frontend from source, tests |
| [Architecture](wiki/Architecture.md) | current **and** target/HA architecture, multi-engine design |
| [Configuration](wiki/Configuration.md) | every environment variable & tuning knob |
| [API Reference](wiki/API-Reference.md) | all REST endpoints + auth |
| [Database Schema](wiki/Database-Schema.md) | metadata-store tables, ER model, migrations |
| [Security](wiki/Security.md) | auth/RBAC, secret encryption, threat model |
| [Monitoring & Operations](wiki/Monitoring-and-Operations.md) | metrics, dashboards, logs, alerting |
| [Deployment](wiki/Deployment.md) | compose, Kubernetes, CI/CD, topologies |
| [Scaling & Capacity Planning](wiki/Scaling-and-Capacity-Planning.md) | levers, sizing, limits |
| [Disaster Recovery](wiki/Disaster-Recovery.md) | backup scope, restore runbooks, RTO/RPO |
| [Troubleshooting](wiki/Troubleshooting.md) | common failures and fixes |
| [Roadmap](wiki/Roadmap.md) | delivered & planned |

## Deep-dive references

Focused technical notes and records kept alongside the wiki (linked from the relevant pages):

| Doc | Topic |
|---|---|
| [ADR-0001-tech-stack.md](ADR-0001-tech-stack.md) | Architecture decision record: technology stack |
| [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) | Architecture review & gap analysis (historical) |
| [MULTI-ENGINE.md](MULTI-ENGINE.md) | Per-engine CDC details & caveats (e.g. MySQL 8.4) |
| [CDC-HARDENING.md](CDC-HARDENING.md) | Schema evolution, large-table snapshot, lag tuning knobs |
| [SCHEDULING.md](SCHEDULING.md) | Scheduling & orchestration internals |
| [ADVANCED-FEATURES.md](ADVANCED-FEATURES.md) | Planning, validation, profiling, recommendations detail |
| [CROSS-DB-TEST-MATRIX.md](CROSS-DB-TEST-MATRIX.md) | Verified end-to-end engine matrix + bugs fixed |
| [SECURITY-database-accounts.md](SECURITY-database-accounts.md) | Least-privilege source/target DB accounts |
| [SINK-STARTUP-LATENCY.md](SINK-STARTUP-LATENCY.md) | Investigation: sink cold-start latency fix (#130) |
| [GAP-ANALYSIS.md](GAP-ANALYSIS.md) | Application hardening gap analysis (epic #122, historical) |

> Consolidation note (epic #17 / #68): the previously overlapping top-level docs (`API.md`,
> `SECURITY.md`, `AUTH-RBAC.md`, `MONITORING.md`, `ROADMAP.md`, `BACKUP-DR.md`, `HA-TOPOLOGY.md`)
> are now one-line pointers to their wiki pages.

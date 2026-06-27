# Roadmap

The platform foundation and the application-hardening / gap-closure epic are complete (engines, control plane, UI, one-command stack, observability, tests). This page tracks the direction beyond that. See `docs/ROADMAP.md` in the repo for the authoritative, evolving list.

## Recently delivered

- Any-to-any engine support (SQL Server, PostgreSQL, MySQL, Oracle, Db2, MongoDB source).
- Full job lifecycle with **live per-table progress** and **re-run full load**.
- Validation: reconciliation (count/checksum) + deep **integrity report** with CSV export.
- Planning, dry-run, cost estimate, type recommendations, profiling/PII, remediation hints.
- Server-side **pagination + filtering** on all list endpoints.
- **Route-level code-splitting** + a **Vitest + Playwright** suite in CI.
- Custom **SMT baked into the Connect image**; sink cold-start latency fixed.
- **One-command full stack** (control plane + data plane + Prometheus/Grafana/Loki).

## Near-term candidates

- **Connect JMX metrics** — enable the JMX exporter so Prometheus scrapes Debezium snapshot/streaming metrics directly (the `kafka-connect-jmx` target is pre-wired).
- **Per-table completion from source counts** — mark a table COMPLETED by comparing synced rows to the source row count, not just snapshot-done.
- **Richer alerting** — more alert types, severities, and notification channels (email/PagerDuty in addition to webhook).
- **Frontend test depth** — expand component/e2e coverage beyond the smoke path.

## Medium-term

- **Schema evolution UX** — surface and approve target DDL changes as the source schema drifts.
- **More target dialects / sink tuning** — per-engine batch sizing, insert modes, and upsert strategies exposed in the UI.
- **Transformation rules** — user-defined column/value transforms beyond UUID/JSON and naming.
- **Multi-tenant / project ownership** — scope projects/connections to teams with finer-grained RBAC.

## Longer-term

- **Horizontal scale guidance & autoscaling** — documented reference topologies on Kubernetes with Strimzi, plus capacity planning.
- **Disaster-recovery automation** — one-click metadata backup/restore and offset snapshotting.
- **Pluggable extensions** — formalize the SPI (`/plugins`) so third-party engines, dialects, validators, and transforms can be dropped in.

## Reference docs (in-repo)

`docs/ROADMAP.md`, `docs/HA-TOPOLOGY.md`, `docs/BACKUP-DR.md`, `docs/MULTI-ENGINE.md`, `docs/ADVANCED-FEATURES.md`, `docs/CDC-HARDENING.md`, `docs/ADR-0001-tech-stack.md`.

> Have a request? Open a GitHub issue and link it to the relevant area above.

# Documentation

The **source of truth** for the project **[Wiki](../../../../wiki)**. Edit the docs here (reviewed via PRs); on merge to `main`, the [`Sync Wiki`](../../.github/workflows/wiki-sync.yml) workflow republishes them to the live wiki automatically — so both locations stay in sync. The live wiki is the browsable home; this folder travels with the code.

## Contents

**Get started**
- [Home](Home.md) — overview & architecture at a glance
- [Getting Started](Getting-Started.md) — prerequisites, one-command run, first login
- [Running a Migration](Running-a-Migration.md) — end-to-end runbook + CDC prerequisites
- [Local Development](Local-Development.md) — run backend/frontend from source, tests, conventions

**Reference**
- [Architecture](Architecture.md) — control/data plane, packages, multi-engine design
- [Configuration](Configuration.md) — every environment variable & tuning knob
- [API Reference](API-Reference.md) — all REST endpoints + auth
- [Database Schema](Database-Schema.md) — metadata-store tables, ER model, migrations

**Operations**
- [Security](Security.md) — auth/RBAC, secret encryption, threat model & hardening
- [Monitoring & Operations](Monitoring-and-Operations.md) — metrics, dashboards, logs, scaling, DR
- [Deployment](Deployment.md) — compose, Kubernetes, CI/CD, topologies
- [Troubleshooting](Troubleshooting.md) — common failures and fixes

**Project**
- [Roadmap](Roadmap.md) — delivered & planned

> Edit pages **here**, not in the wiki UI — wiki edits would be overwritten on the next sync. The design notes and ADRs in the parent [`docs/`](../) folder remain the authoritative source for deep-dive topics.

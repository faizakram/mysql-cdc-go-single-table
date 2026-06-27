# Documentation

A version-controlled copy of the project **[Wiki](../../../../wiki)**. The wiki is the primary, browsable home for these docs; this folder is the backup that travels with the code and is reviewed via PRs.

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

> Keep this copy and the wiki in sync when you change either. The design notes and ADRs in the parent [`docs/`](../) folder remain the authoritative source for deep-dive topics.

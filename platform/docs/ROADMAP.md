# Database Migration Platform — Production Roadmap

A Principal-Architect / QA-Lead / PM / DB-Expert view of the product vision: what already exists,
the net-new epics to reach a world-class any-to-any migration platform, and the cross-cutting
deliverables (acceptance criteria, Definition of Done, production-readiness checklist, risk
analysis, future enhancements).

> Scope note: ~11 epics that cover the vision are **already built and closed** in this repo (see the
> mapping below). This roadmap focuses on the **net-new gaps**. Net-new epics are tracked as GitHub
> issues in Project 22; this document is the detailed companion (stories, subtasks, QA, cross-DB
> tests, acceptance criteria).

---

## 1. Vision → current state

| Vision area | Status | Reference |
|-------------|--------|-----------|
| Any-to-any **relational** engines | ✅ Done | Epic #76 (SQL Server, PostgreSQL, MySQL, Oracle, Db2) |
| Smart data-type mapping + override + configurable engine | ✅ Done | #81 `TypeMappingMatrix`, #29/#31/#37 |
| Schema analysis: tables/columns/PK/FK/indexes + CDC flag | ◑ Partial | #30 — extended by **Epic C** |
| Real-time dashboard (tables, rows, speed, ETA, CDC lag/health) | ✅ Done | #39, #12, #50 |
| CDC sync + lag + retries + replication health | ✅ Done | core, #28, #52 |
| Validation: row count + checksum (soft-delete aware) | ◑ Partial | #49 — extended by **Epic D** |
| Error reporting (table/column/value/reason/retry) | ✅ Done | #41 |
| Configuration (batch, parallelism, retry, CDC, logging) | ✅ Done | #38, #27 |
| Security, secrets, TLS, least-privilege | ✅ Done | Epic #10 |
| RBAC + auth + audit | ✅ Done | Epic #14 |
| Monitoring/observability (Prometheus/Grafana/Loki/alerts) | ✅ Done | Epic #12 |
| Scheduling + job queue/concurrency | ✅ Done | Epic #13 |
| CI/CD, HA, backup/DR, K8s, destructive-op guards | ✅ Done | Epic #16, #64 |
| **Naming-strategy preservation** | ❌ Gap (conflict) | **Epic A** — SMT currently force-renames to snake_case |
| **Intelligent migration planning** | ❌ Gap | **Epic B** |
| **Extended schema objects** (seq/identity/defaults/views/triggers/procs/functions) | ❌ Gap | **Epic C** |
| **Deep validation** (null/PK/FK/dupes/invalid-type/missing-record/report) | ❌ Gap | **Epic D** |
| **Non-relational sources (MongoDB)** | ❌ Gap | **Epic E** |
| **Dry-run, rollback, resumability** | ❌ Gap | **Epic F** |
| **AI-assisted recommendations + cost estimation** | ❌ Gap | **Epic G** |
| **Data quality, profiling, PII/compliance** | ❌ Gap | **Epic H** |
| **Formal plugin SPI** | ◑ Partial | **Epic I** (engine catalog is extensible; needs a real SPI) |
| **Scale + cross-DB test program (1M/10M/100M)** | ◑ Partial | extends Testing Epic #15 |

---

## 2. Net-new epic hierarchy

Each epic lists its **user stories**, **technical tasks**, **QA tasks**, **cross-DB tasks**, and
**acceptance criteria**. Stories are created as GitHub issues; subtasks live as checklists.

### Epic A — Naming Strategy & Identifier Preservation  *(priority: high)*
**Why:** The transform chain today force-renames every table/column to snake_case. Enterprises need
names preserved by default, with opt-in conversion and correct case/quoting per dialect.

**User stories**
- As an operator, names are **preserved exactly** by default so downstream apps keep working.
- As an operator, I can choose a naming strategy per project: `preserve | snake_case | camelCase | PascalCase | UPPER_CASE`.
- As an operator, case sensitivity and reserved-word quoting are handled correctly per target dialect.
- As an operator, I can preview the resulting names before running.

**Technical tasks**
- Make the snake_case SMT **opt-in**; add a `namingStrategy` project config (default `preserve`).
- Per-dialect identifier quoting/case-folding (PostgreSQL lowercases unquoted; Oracle uppercases; etc.).
- Reserved-word detection + automatic quoting.
- Name-collision detection when a strategy maps two names to one.

**QA tasks** — unit tests per strategy × dialect; round-trip case-sensitivity tests; reserved-word corpus.
**Cross-DB tasks** — verify identifier handling for each target engine (quoting/case).
**Acceptance criteria** — default run preserves names byte-for-byte; each strategy verified; reserved words quoted; collisions reported, not silently merged.

### Epic B — Intelligent Migration Planning  *(priority: high)*
**User stories** — auto-generated plan with table order, parallel groups, estimates, and risks before I run.

**Technical tasks**
- Build FK dependency graph; **topological order**; detect circular references → break with deferred constraints.
- Identify independent table groups for **parallel** migration.
- Estimate **row counts, storage, duration** (sample + statistics).
- **Risk analysis**: no-PK tables, unsupported types, huge tables, circular FKs, wide rows.
- Persist the plan; expose via API + a plan view (Gantt/order).

**QA tasks** — graph correctness on synthetic schemas (chains, diamonds, cycles); estimate accuracy bands.
**Cross-DB tasks** — plan generation against each source engine's catalog.
**Acceptance criteria** — correct dependency order; cycles detected + handled; estimates within a stated tolerance; risks surfaced with severity.

### Epic C — Extended Schema-Object Migration  *(priority: high)*
**User stories** — migrate not just tables but sequences, identity, defaults, views, triggers, procedures, functions (or get a clear report when an object can't be auto-translated).

**Technical tasks** — discovery + DDL generation for: sequences/identity; default & generated columns; views; triggers (best-effort + report); stored procedures & functions (translate where feasible, else report); constraint & secondary-index parity.
**QA tasks** — per-object-type DDL fidelity tests.
**Cross-DB tasks** — object-support matrix per engine pair (what translates vs. reports).
**Acceptance criteria** — supported objects migrate with verified DDL; unsupported objects produce an actionable report, never silent loss.

### Epic D — Advanced Validation & Reconciliation  *(priority: high; extends #49)*
**Technical tasks** — null-count & duplicate/distinct checks; post-migration PK/FK/unique validation; key-level **missing/extra record diff**; invalid/lossy type-conversion detection; **exportable report** (CSV/JSON/PDF).
**QA tasks** — seeded mismatches (missing rows, dupes, truncated decimals) must be caught.
**Cross-DB tasks** — checksum normalization per engine pair.
**Acceptance criteria** — all listed checks run and report; seeded defects detected 100%; report downloadable.

### Epic E — Non-Relational Sources (MongoDB)  *(priority: medium)*
**Technical tasks** — MongoDB source connector + catalog entry; schema inference for schemaless collections; document→relational flattening (nested/array handling, JSON columns); CDC via change streams.
**QA tasks** — inference correctness on heterogeneous documents; nested/array fidelity.
**Cross-DB tasks** — MongoDB → PostgreSQL/MySQL.
**Acceptance criteria** — a collection migrates to a relational target with documented flattening rules; nested data preserved (as columns or JSON); CDC streams changes.

### Epic F — Migration Simulation, Rollback & Recovery  *(priority: high)*
**Technical tasks** — **dry-run** (plan + validate connectivity/types, zero writes); pre-migration target **snapshot/checkpoint**; **rollback** to checkpoint; **resumable/idempotent** restart after failure.
**QA tasks** — kill-mid-migration → resume → reconcile; rollback restores prior state.
**Acceptance criteria** — dry-run writes nothing and surfaces issues; rollback restores target; interrupted runs resume without duplication.

### Epic G — AI-Assisted Migration Intelligence  *(priority: medium)*
**Technical tasks** — AI type-mapping **recommendations with rationale**; risk/remediation suggestions on errors; **cost estimation** (compute/storage/time/$); optional NL migration assistant.
**QA tasks** — recommendation quality benchmark vs. the deterministic matrix; guardrails (never auto-apply without confirm).
**Acceptance criteria** — suggestions are explainable and overridable; cost estimate produced per plan; AI never silently changes a mapping.

### Epic H — Data Quality, Profiling & Compliance  *(priority: medium)*
**Technical tasks** — column **profiling** (cardinality, min/max, null %, patterns); anomaly/outlier detection; **PII detection + masking** during migration; compliance controls (GDPR, data residency, retention).
**QA tasks** — PII detection precision/recall on a labeled set; masking irreversibility.
**Acceptance criteria** — profile report per table; PII flagged + maskable; residency/retention enforced and audited.

### Epic I — Plugin Architecture & Extensibility (SPI)  *(priority: medium)*
**Technical tasks** — formal **SPI** for engines, type-mappers, validators, transforms; plugin discovery/loading + versioning; developer guide + sample plugin.
**Acceptance criteria** — a new engine/mapper/validator can be added as a plugin without modifying core; versioned + documented; sample plugin loads.

### Testing Epic #15 — additions (scale & cross-DB)
- **Performance/scale suite**: 1M / 10M / 100M rows — measure throughput, memory, CPU, network, completion time.
- **Cross-database compatibility matrix automation**: every supported pair, scheduled.
- **Edge-case corpus**: large CLOB/BLOB, Unicode, NULLs, empty/huge tables, composite & circular keys, reserved words, special characters, time zones, decimal precision, date conversions.

---

## 3. Global Definition of Done
A story is Done when: code + unit/integration tests merged and **CI green**; feature flagged/configurable with safe defaults; **observable** (metrics/logs/alerts) where relevant; **secured** (authz + secrets + TLS) where relevant; **documented** (user + ops); **backward-compatible** or migration noted; verified against ≥1 live engine pair (or explicitly marked test-matrix-only); no plaintext secrets; reviewed.

## 4. Production-Readiness Checklist
- [ ] All control/config actions authenticated (JWT), authorized (RBAC), and audited
- [ ] Secrets encrypted at rest + externalized to a manager; TLS on all channels
- [ ] No destructive operation without explicit, guarded opt-in
- [ ] Metrics + dashboards + alerting for connector/job/lag/error state
- [ ] HA control plane (stateless, ≥2 replicas) + HA Kafka/Connect; metadata-store PITR
- [ ] Backup/restore + tested DR runbook; rollback path verified
- [ ] Resumable migrations; idempotent sink (upsert); no duplication on retry
- [ ] Validation passes (row/checksum/null/PK/FK/dupe) before sign-off
- [ ] Capacity/perf tested at target scale (≥ expected peak rows)
- [ ] Least-privilege DB accounts (no sa/superuser); secrets rotated
- [ ] CI: build/lint/test/security-scan; SBOM; pinned actions
- [ ] Runbooks: onboarding, incident, rollback, cutover

## 5. Risk Analysis
| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Type-mapping data loss (precision, lossy types) | Med | High | Matrix + flags + validation; AI suggestions; dry-run |
| Naming changes break downstream apps | Med | High | **Epic A** preserve-by-default |
| Circular FKs / ordering errors | Med | Med | Topological plan + deferred constraints (**Epic B**) |
| Unsupported objects silently dropped | Med | High | **Epic C** report-not-drop policy |
| CDC lag / backpressure under load | Med | Med | Lag metric + alerts (#28/#50); scale tests (#15) |
| Failure mid-migration → partial/dup data | Med | High | Resumable + idempotent + rollback (**Epic F**) |
| Secret leakage | Low | High | Encryption + externalized provider + isolation (#10) |
| Engine-specific edge cases (CLOB/Unicode/TZ) | High | Med | Edge-case corpus (#15) |
| Scale beyond tested limits | Med | High | Perf suite + capacity planning (#15, HA-TOPOLOGY) |

## 6. Future Enhancements
Multi-region active/active replication · cross-cloud migration (RDS↔Cloud SQL↔Azure SQL) ·
schema-drift continuous monitoring · automated cutover orchestration (read-only window, switchover) ·
data-diff streaming (continuous validation) · cost/usage chargeback · self-service catalog & approvals ·
marketplace of community plugins · graph/columnar/warehouse targets (Snowflake/BigQuery/Redshift) ·
ML-based anomaly detection on replicated data · migration "blue/green" with traffic shadowing.

## 7. Enterprise pillars — coverage map
Security ✅ (#10/#14) · Observability/Monitoring ✅ (#12) · Auditing ✅ (#14) · RBAC ✅ (#14) ·
Scalability/HA ✅ (#16/#64) · Backup/DR ✅ (#16) · Rollback ▶ Epic F · DR drills ✅ (BACKUP-DR) ·
Performance ▶ #15 scale suite · Cost estimation ▶ Epic G · Dry run ▶ Epic F · Data quality ▶ Epic H ·
Compliance ▶ Epic H · Cloud/K8s ✅ (#64) · Plugin architecture ▶ Epic I · AI assistance ▶ Epic G.

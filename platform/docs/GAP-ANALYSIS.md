# Application gap analysis & hardening plan

An end-to-end audit (UI, backend, ops/process) of the migration platform, with evidence and a
prioritized fix plan. Tracked under the **Application Hardening & Gap Closure** epic.

## Findings

### High priority
| # | Area | Gap (evidence) | Fix |
|---|------|----------------|-----|
| G1 | UI | New backend features have **APIs but no UI**: advanced validation report (#96), data profiling + PII (#112/#114), AI recommendations (#108), plugin registry (#116). Only dry-run + plan/cost are surfaced. | Add screens/drawers: Validation report (+CSV), Profiling/PII, Recommendations, Plugins; wire remediation hints (#111) into the Errors drawer. |
| G2 | Backend | **MongoDB connections can't be tested/discovered** — `JdbcSupport` has no Mongo handling, so Test-connection, schema discovery and CDC-readiness fall through the JDBC path and fail for Mongo. | Add a native Mongo client path for test/discovery (or guard + a Mongo-specific readiness/discovery). |
| G3 | DevOps | **Actuator health probe groups not configured** — `application.yml` exposes `health` only, but `deploy/k8s/20-platform-backend.yaml` probes `/actuator/health/{readiness,liveness}`, which won't exist → K8s probes fail. | Enable `management.endpoint.health.probes.enabled=true` + liveness/readiness groups. |
| G6 | Testing | **No frontend tests** (`find` for `*.test/*.spec` returns nothing) — story #60 unmet. | Add Vitest component tests + a Playwright smoke (login → dashboard → create project). |

### Medium priority
| # | Area | Gap | Fix |
|---|------|-----|-----|
| G4 | Backend | **No pagination** on list endpoints (`projects`, `connections`, `users` use `findAll()`); won't scale to many rows. | Page + filter the list APIs and the UI tables. |
| G5 | UI | **No code-splitting** — single ~1.3 MB JS chunk (Vite build warns). | Route-level `React.lazy` + manualChunks (split antd/vendor). |
| G7 | CDC | **Per-table snapshot progress empty** — the Jobs drawer shows "No per-table status"; Debezium snapshot row counts aren't recorded (#19 partial). | Consume snapshot progress (JMX/metrics or snapshot markers) → populate `table_status`. |
| G8 | Ops | **~5-min sink consumer-join latency** before a new sink writes (observed on every fresh sink). | Tune Connect/Kafka (`metadata.max.age.ms`, topic auto-create/precreate, consumer `metadata` refresh); document expected timing. |
| G9 | CDC | **Full reload needs manual offset surgery** — restarting a job resumes from committed offsets (no re-snapshot); no UI action to reset. | Add a "Re-run full load" action (reset offsets / fresh capture instance / new connector name). |

### Low priority
| # | Area | Gap | Fix |
|---|------|-----|-----|
| G10 | DevOps | camelCase/Pascal/UPPER naming needs the rebuilt **SMT jar manually mounted** into Connect. | Bake the SMT jar into a custom Connect image in CI so all naming strategies work out-of-the-box. |
| G11 | Process | Leftover **`TEST`/`RETRY` test projects** clutter the Projects list. | Delete the throwaway test projects + their connectors/connections (housekeeping). |

## Known limitations (documented, not bugs)
- Rollback is **truncate-to-empty**, not point-in-time snapshot restore (#104).
- AI recommendations are **rule-based + explainable**, LLM-pluggable later (#108).
- **Oracle/Db2 not live-verified** — amd64-only/licensing; config + types unit-tested (CROSS-DB-TEST-MATRIX.md).

## Sequencing
1. **Correctness/ops first:** G2 (Mongo), G3 (health probes) — they break real deployments.
2. **Value/visibility:** G1 (surface features in UI), G7 (per-table progress).
3. **Scale/quality:** G4 (pagination), G5 (code-splitting), G6 (frontend tests).
4. **Polish/ops:** G8 (latency), G9 (full-load action), G10 (SMT bake), G11 (cleanup).

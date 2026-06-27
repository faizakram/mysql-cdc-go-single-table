# Scheduling & Job Orchestration (epic #13)

Run full-load and validation jobs on a cron, and govern how many run at once.

## Scheduler (#53)

Each project can have any number of **schedules** (`job_schedule` table), managed from the project's
**Schedules** drawer or the API:

| Field | Meaning |
|-------|---------|
| `kind` | `FULL_LOAD` (deploy connectors + snapshot/CDC job) or `VALIDATION` (reconciliation) |
| `cron` | Spring 6-field cron — `sec min hour day-of-month month day-of-week` |
| `enabled` | toggle without deleting |
| `lastRunAt` / `lastStatus` | last fire time + `SUCCESS` / `FAILED` / `RUNNING` |
| `nextRunAt` | next fire time, computed from the cron |

A sweeper (`SchedulerSweeper`, default every 30s — `SCHEDULER_SWEEP_CRON`) enqueues every enabled
schedule whose `nextRunAt` is due, then advances `nextRunAt`. **Run now** enqueues immediately,
bypassing the cron. Examples: `0 0 2 * * *` = daily 02:00; `0 */15 * * * *` = every 15 min.

API: `GET/POST /api/v1/projects/{id}/schedules`, `PUT/DELETE /api/v1/schedules/{id}`,
`POST /api/v1/schedules/{id}/run-now`.

## Job queue & concurrency control (#54)

All scheduled and manual runs go through the in-process **`JobOrchestrator`**:

- **Concurrency limit** — at most `ORCHESTRATOR_MAX_CONCURRENT` tasks (default 2) run at once across
  all projects. Excess submissions **queue** (backpressure) rather than overwhelming the data plane.
- **Per-project fairness** — at most one task per project runs at a time, so a heavy snapshot for
  one project never blocks another, and two runs of the same project never overlap.
- **Observable** — `GET /api/v1/orchestrator/status` returns the limit plus the running and queued
  tasks; the Dashboard's **Job queue** panel renders this live (running / queued / limit).

This prevents resource exhaustion from many projects snapshotting simultaneously, satisfying #54's
"concurrency limits enforced / queue observable / no resource exhaustion" criteria. Concurrency and
fairness are covered by `JobOrchestratorTest`.

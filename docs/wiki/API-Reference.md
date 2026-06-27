# API Reference

REST API for the control plane. **Base path:** `/api/v1`. Interactive docs (Swagger UI): **http://localhost:8090/swagger-ui.html**; OpenAPI JSON at `/v3/api-docs`.

## Authentication

All endpoints except login, health, and Swagger require a **Bearer JWT**:

```
Authorization: Bearer <token>
```

Get a token:

```bash
curl -s -X POST http://localhost:8090/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}'
# → { "token": "...", "username": "admin", "role": "ADMIN", "expiresInMinutes": 480 }
```

Roles: **ADMIN**, **OPERATOR**, **VIEWER**. Reads (`GET /api/**`) require any role; writes (`POST/PUT/PATCH/DELETE`) require OPERATOR or ADMIN; `/users` and `/audit` require ADMIN. See [Security](Security.md).

---

## Auth
| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/auth/login` | Authenticate; returns JWT. | Public |
| POST | `/auth/refresh` | Exchange a valid token for a fresh one. | Any |
| GET | `/auth/me` | Current user (username, role). | Any |

## Connections
| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/connections` | List (paged: `page,size,q,dbType`). | Any |
| GET | `/connections/engines` | Supported engine catalog. | Any |
| GET | `/connections/{id}` | Get one. | Any |
| POST | `/connections` | Create. | OPERATOR+ |
| PUT | `/connections/{id}` | Update. | OPERATOR+ |
| DELETE | `/connections/{id}` | Delete. | OPERATOR+ |
| POST | `/connections/{id}/test` | Test a saved connection. | OPERATOR+ |
| POST | `/connections/test` | Test ad-hoc params (form preview). | OPERATOR+ |
| GET | `/connections/{id}/cdc-readiness` | Check CDC prerequisites. | Any |
| GET | `/connections/{id}/schema-objects` | Inventory of views/sequences/functions. | Any |
| GET | `/connections/{id}/profile` | Column profiling + PII flags (`schema,table`). | Any |

## Schema discovery & mapping
| Method | Path | Description |
|---|---|---|
| GET | `/connections/{id}/schema/tables` | List tables (optional `schema`). |
| GET | `/connections/{id}/schema/columns` | List columns (`schema,table`). |
| GET | `/connections/{id}/schema/type-mapping` | Proposed type mappings (`schema,table,projectId?`). |

## Projects
| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/projects` | List (paged: `page,size,q,status`). | Any |
| GET | `/projects/{id}` | Get one. | Any |
| POST | `/projects` | Create. | OPERATOR+ |
| PUT | `/projects/{id}` | Update config. | OPERATOR+ |
| DELETE | `/projects/{id}` | Delete (cascades jobs/schedules/recon). | OPERATOR+ |

### Project planning, validation & intelligence
| Method | Path | Description |
|---|---|---|
| POST | `/projects/{id}/dry-run` | Validate end-to-end without deploying. |
| GET | `/projects/{id}/plan` | Dependency-ordered migration plan. |
| GET | `/projects/{id}/cost-estimate` | Estimated compute/storage/duration. |
| GET | `/projects/{id}/recommendations` | Type/config recommendations. |
| GET | `/projects/{id}/validation` | Integrity report (null PKs, dup keys, missing/extra). |
| GET | `/projects/{id}/validation/report.csv` | Integrity report as CSV. |
| GET | `/projects/{id}/schema/constraints/ddl` | Preview constraint/index DDL. |
| POST | `/projects/{id}/schema/constraints/apply` | Apply constraints to target (idempotent). |
| POST | `/remediation` | Suggest a fix for an error message (`{error}` → `{hint}`). |

## Jobs (runs)
| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/projects/{id}/jobs` | List runs for a project. | Any |
| POST | `/projects/{id}/jobs` | Create a run. | OPERATOR+ |
| GET | `/projects/{id}/connector-preview` | Preview generated connector configs (secrets masked). | Any |
| GET | `/jobs/{id}` | Get a run. | Any |
| GET | `/jobs/{id}/tables` | Per-table status (phase, status, rows synced). | Any |
| POST | `/jobs/{id}/start` | Deploy connectors & start. | OPERATOR+ |
| POST | `/jobs/{id}/pause` | Pause connectors. | OPERATOR+ |
| POST | `/jobs/{id}/resume` | Resume. | OPERATOR+ |
| POST | `/jobs/{id}/stop` | Remove connectors. | OPERATOR+ |
| POST | `/jobs/{id}/reload` | Reset offsets → clean re-snapshot. | OPERATOR+ |

## Reconciliation
| Method | Path | Description |
|---|---|---|
| POST | `/projects/{id}/reconciliation` | Run (`mode=COUNT|CHECKSUM`, `sampleSize`). |
| GET | `/projects/{id}/reconciliation` | History of runs. |
| GET | `/reconciliation/{runId}/report.csv` | Run report as CSV. |

## Monitoring & orchestration
| Method | Path | Description |
|---|---|---|
| GET | `/monitoring/overview` | Health for all projects with deployed connectors. |
| GET | `/monitoring/projects/{id}` | Live health (connector states, lag) for a project. |
| GET | `/orchestrator/status` | Job queue & concurrency state. |

## Alerts
| Method | Path | Description |
|---|---|---|
| GET | `/alerts` | List (optional `projectId`). |
| GET | `/alerts/count` | Firing-alert count (UI badge). |
| POST | `/alerts/{id}/acknowledge` | Acknowledge an alert. |

## Schedules
| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/projects/{id}/schedules` | List schedules. | Any |
| POST | `/projects/{id}/schedules` | Create (cron, `FULL_LOAD`/`VALIDATION`). | OPERATOR+ |
| PUT | `/schedules/{id}` | Update. | OPERATOR+ |
| DELETE | `/schedules/{id}` | Delete. | OPERATOR+ |
| POST | `/schedules/{id}/run-now` | Trigger immediately. | OPERATOR+ |

## Users & Audit (ADMIN)
| Method | Path | Description |
|---|---|---|
| GET | `/users` | List (paged: `page,size,q,role,enabled`). |
| POST | `/users` | Create (`username,password,role`). |
| PATCH | `/users/{id}` | Update role/enabled/password. |
| DELETE | `/users/{id}` | Delete. |
| GET | `/audit` | Audit entries (paged: `page,size,actor,action`). |

## Plugins
| Method | Path | Description |
|---|---|---|
| GET | `/plugins` | SPI-loaded engines/dialects/transforms. |

---

## Conventions

- **Paged lists** return `{ content: [...], page, size, total }`. Filters are query params (e.g. `?page=0&size=20&q=foo&status=ACTIVE`).
- **Errors** use a consistent envelope: `{ status, error, message, details[] }`. `401` = missing/invalid token; `403` = role not permitted; `404` = not found; `400` = validation error.
- **Secrets** (connection passwords) are never returned in any response.

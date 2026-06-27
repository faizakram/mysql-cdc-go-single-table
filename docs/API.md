# Control-plane API reference (issue #24)

Base path: **`/api/v1`** · Format: JSON · Auth: Bearer JWT (except `POST /api/v1/auth/login`).

## Versioning
All endpoints are namespaced under `/api/v1`. Breaking changes ship under a new version prefix
(`/api/v2`); additive changes (new fields/endpoints) stay within `v1`.

## Live spec & docs
- OpenAPI JSON: **`GET /v3/api-docs`** (served by springdoc; validated in CI by `OpenApiContractIT`).
- Swagger UI: **`/swagger-ui.html`**.

These are generated from the controllers, so they are always in sync with the running build — treat
them as the source of truth; this file is the human overview.

## Authentication
1. `POST /api/v1/auth/login` `{ "username", "password" }` → `{ token, username, role, expiresInMinutes }`.
2. Send `Authorization: Bearer <token>` on every other request.
3. `GET /api/v1/auth/me` → current principal. A 401 means the token is missing/expired.

## Authorization (RBAC)
| Role | Capability |
|---|---|
| `VIEWER` | read-only (all `GET`) |
| `OPERATOR` | reads + all mutations/operations (connections, projects, jobs, validation) |
| `ADMIN` | everything, incl. `…/users/**` user administration |

## Error envelope
Every error — validation, not-found, conflict, **401, 403**, and unexpected — returns the same shape:
```json
{
  "timestamp": "2026-06-26T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot pause a job in state STOPPED",
  "details": ["field: reason", "..."]
}
```
| Status | When |
|---|---|
| 400 | validation failure, invalid argument, invalid job-state transition |
| 401 | missing/expired/invalid token |
| 403 | authenticated but role not permitted |
| 404 | entity not found |
| 500 | unexpected server error |

## Endpoint catalog (overview)
| Area | Method & path |
|---|---|
| Auth | `POST /auth/login`, `GET /auth/me` |
| Connections | `GET/POST /connections`, `GET/PUT/DELETE /connections/{id}`, `POST /connections/test`, `POST /connections/{id}/test` |
| Schema | `GET /connections/{id}/schema/tables`, `…/columns`, `…/type-mapping` |
| Projects | `GET/POST /projects`, `GET/PUT/DELETE /projects/{id}` |
| Jobs | `GET/POST /projects/{id}/jobs`, `GET /projects/{id}/connector-preview`, `GET /jobs/{id}`, `POST /jobs/{id}/{start\|pause\|resume\|stop}` |
| Validation | `POST /projects/{id}/reconciliation?mode=COUNT\|CHECKSUM`, `GET /projects/{id}/reconciliation`, `GET /reconciliation/{runId}/report.csv` |
| Monitoring | `GET /monitoring/overview`, `GET /monitoring/projects/{id}` |
| Connect proxy | `GET /connect/connectors`, `GET /connect/connectors/{name}/status`, `PUT …/pause`, `PUT …/resume`, `POST …/restart` |
| Users (ADMIN) | `GET/POST /users`, `PATCH/DELETE /users/{id}` |

Job lifecycle is guarded by a state machine (#23): `start` from CREATED/STOPPED/FAILED, `pause` from
SNAPSHOT/RUNNING, `resume` from PAUSED, `stop` from any non-terminal state — invalid transitions return 400.

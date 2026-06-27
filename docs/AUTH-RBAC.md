# Authentication, RBAC & Audit (epic #14)

## Authentication (#55)

Local username/password auth issuing a signed **JWT** (HMAC-SHA256, `jjwt`). All API endpoints and
UI routes require a valid token; only `POST /api/v1/auth/login`, the health/prometheus actuator
endpoints, and the OpenAPI docs are public.

| Concern | How |
|---------|-----|
| Login | `POST /api/v1/auth/login` → `{ token, username, role, expiresInMinutes }` |
| Token use | `Authorization: Bearer <token>`; validated per-request by `JwtAuthFilter` |
| Expiry | `JWT_TTL_MINUTES` (default 480). Expired/invalid tokens → 401 |
| Refresh | `POST /api/v1/auth/refresh` re-issues a token for the current user; the UI refreshes silently every 15 min so active sessions don't expire mid-use (#55) |
| Logout | Stateless — the client drops the token (`tokenStore.clear()`) and returns to `/login` |
| Secrets | Signing key `JWT_SECRET` (≥32 bytes); passwords hashed with BCrypt |

**Identity-provider / SSO integration.** The token model is standard OIDC-style JWT. To front the
platform with an external IdP (Okta/Entra/Keycloak), terminate OIDC at an ingress/gateway and pass
the validated identity through, or replace `AuthService.login` with an OIDC code-exchange that maps
IdP groups → the roles below. `JwtAuthFilter` stays unchanged as long as the JWT carries `sub`
(username) and a `role` claim.

## Role-based access control (#56)

Three roles, enforced **server-side** in `SecurityConfig` and **reflected in the UI** (unauthorized
actions are hidden/disabled via the auth context).

| Capability | VIEWER | OPERATOR | ADMIN |
|------------|:------:|:--------:|:-----:|
| View dashboards, projects, connections, monitoring, alerts | ✅ | ✅ | ✅ |
| Start / pause / resume / stop jobs | — | ✅ | ✅ |
| Create / edit / delete projects, connections, schedules | — | ✅ | ✅ |
| Run reconciliation, run-now schedules | — | ✅ | ✅ |
| Acknowledge alerts | — | ✅ | ✅ |
| Manage users | — | — | ✅ |
| View audit log | — | — | ✅ |

Enforcement rules (server-side, in order):
- `GET /api/**` → `VIEWER`, `OPERATOR`, or `ADMIN` (reads open to all authenticated roles)
- all other `/api/**` (writes/operations) → `OPERATOR` or `ADMIN`
- `/api/v1/users/**` and `/api/v1/audit/**` → `ADMIN` only

The UI uses the same matrix: `canWrite`/`canControl` gate buttons, and ADMIN-only nav items (Users,
Audit log) are hidden for other roles. Because enforcement is server-side, hiding UI controls is
defense-in-depth, not the security boundary.

## Audit log (#57)

Every control and config action is recorded to the `audit_log` table via `AuditService`, capturing
**actor** (from the security context), **action**, **target**, **details** (JSON), and **timestamp**.

Audited actions: `LOGIN`, `JOB_START` / `JOB_PAUSE` / `JOB_RESUME` / `JOB_STOP`,
`PROJECT_CREATE` / `PROJECT_UPDATE` / `PROJECT_DELETE`,
`CONNECTION_CREATE` / `CONNECTION_UPDATE` / `CONNECTION_DELETE`,
`SCHEDULE_CREATE` / `SCHEDULE_RUN_NOW`.

- **View:** `GET /api/v1/audit` (paged), surfaced as the admin-only **Audit log** screen.
- **Retention:** a daily sweep deletes entries older than `AUDIT_RETENTION_DAYS` (default 90).
- Audit writes are best-effort — a logging failure never blocks the underlying action.

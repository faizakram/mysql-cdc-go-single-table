# Security

Layered security: stateless JWT authentication, role-based authorization, encrypted secrets at rest, and pluggable connector-secret injection. This page documents the model and a production hardening checklist.

## Authentication — JWT (stateless)

- **Login:** `POST /api/v1/auth/login` validates the username + BCrypt password against `app_user`, then `JwtService` issues an HS256 token carrying `sub` (username), `role`, `iat`, `exp`.
- **Signing key:** `platform.auth.jwt-secret` / `JWT_SECRET`, **≥ 32 bytes** (the service refuses to start otherwise). **TTL:** `JWT_TTL_MINUTES` (default 480 = 8h).
- **Per-request:** `JwtAuthFilter` parses `Authorization: Bearer <token>`, verifies the signature, and populates the Spring Security context with `ROLE_<role>`. Invalid/expired tokens clear the context.
- **Refresh:** `POST /api/v1/auth/refresh` swaps a still-valid token for a fresh one (the UI refreshes silently). Sessions are **stateless** (`SessionCreationPolicy.STATELESS`); CSRF is disabled (safe for token-based APIs).

## Authorization — RBAC

Roles: **ADMIN**, **OPERATOR**, **VIEWER**. Enforced in `SecurityConfig`:

| Pattern | Rule |
|---|---|
| `POST /api/v1/auth/login`, `/actuator/health*`, `/actuator/info`, `/actuator/prometheus`, Swagger, `OPTIONS /**` | Public |
| `/api/v1/users/**`, `/api/v1/audit/**` | **ADMIN** |
| `GET /api/**` | VIEWER, OPERATOR, ADMIN (read) |
| `POST/PUT/PATCH/DELETE /api/**` | OPERATOR, ADMIN (write) |
| anything else | authenticated |

**Bootstrap admin:** on first start (no users), an admin is created from `ADMIN_USERNAME`/`ADMIN_PASSWORD` (default `admin`/`admin`) with a warning to change it. Passwords are hashed with **BCrypt**.

## Secret encryption — AES-256-GCM

Connection passwords are encrypted at rest by `CryptoService` and **never returned by the API** (even to ADMIN).

- **Cipher:** AES/GCM/NoPadding, 256-bit key, 12-byte random IV per encryption, 128-bit auth tag.
- **Stored form:** `base64( iv[12] || ciphertext+tag )`.
- **Key:** `platform.crypto.key` / `PLATFORM_CRYPTO_KEY` — base64 of exactly **32 bytes** (validated at startup). Generate one with `openssl rand -base64 32`.

## Connector secret providers

How the source/target DB password reaches a Kafka Connect connector config (`ConnectorSecretProperties`, `CONNECT_SECRET_MODE`):

| Mode | Emitted value | Use when |
|---|---|---|
| `inline` (default) | plaintext password in the config | local dev, with Connect REST locked to the control plane |
| `file` | `${file:<path>/<role>.properties:password}` | K8s/Docker with a mounted secret volume |
| `env` | `${env:<PREFIX><ROLE>_PASSWORD}` | K8s with injected env vars |

For `file`/`env`, Kafka Connect must declare the providers:

```properties
config.providers=file,env
config.providers.file.class=org.apache.kafka.common.config.provider.FileConfigProvider
config.providers.env.class=org.apache.kafka.common.config.provider.EnvVarConfigProvider
```

## Transport & CORS

- **TLS:** `SERVER_SSL_ENABLED=true` + a PKCS12 keystore, or terminate TLS at an ingress/LB.
- **CORS:** `CORS_ALLOWED_ORIGINS` (default the dev origin) applied to `/api/**`.
- **Kafka Connect:** optional Basic auth (`KAFKA_CONNECT_USER`/`PASSWORD`); never expose Connect's REST publicly — only the control plane should reach it.

## Audit

Every control-plane mutation is recorded in `audit_log` (actor, action, target, details, timestamp) — `JOB_START/STOP`, `PROJECT_CREATE/UPDATE`, `CONNECTION_DELETE`, `LOGIN`, etc. Read-only, ADMIN-only at `GET /api/v1/audit`. Retention is `AUDIT_RETENTION_DAYS` (default 90).

## Threat model (summary)

| Protected | How |
|---|---|
| User credentials | BCrypt hashes; never stored/returned in plaintext |
| API tokens | HS256-signed; unforgeable without the secret |
| Connection passwords | AES-256-GCM at rest; never returned; resolved only at config-generation time |
| Privilege boundaries | RBAC enforced per endpoint |
| Tamper evidence | Immutable audit log |

## Production hardening checklist

- [ ] Override `JWT_SECRET` (random ≥ 32 bytes) and `PLATFORM_CRYPTO_KEY` (base64 32 bytes), unique per environment.
- [ ] Override `ADMIN_PASSWORD`; change it after first login; create per-person accounts with least-privilege roles.
- [ ] Enable TLS (`SERVER_SSL_ENABLED`) or terminate at an ingress.
- [ ] Restrict `CORS_ALLOWED_ORIGINS` to your real frontend origin.
- [ ] Use `CONNECT_SECRET_MODE=file` or `env` (not `inline`) so plaintext never lands in the Connect config topic.
- [ ] Lock down Kafka Connect REST to the control-plane network; set Basic-auth if reachable.
- [ ] Use an **external** metadata Postgres with TLS, backups, and a strong password.
- [ ] Add rate-limiting/WAF in front of `/auth/login` (no built-in throttling).
- [ ] Rotate the crypto/JWT keys periodically (coordinated rollout).
- [ ] Review the audit log for anomalous admin activity.

> See `docs/SECURITY.md` and `docs/SECURITY-database-accounts.md` (and `deploy/sql/*-least-privilege.sql`) for least-privilege database account scripts.

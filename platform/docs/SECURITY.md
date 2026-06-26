# Security, Secrets & Compliance (epic #10)

Covers credential handling (#43), TLS (#44), and Kafka Connect isolation (#45). Database
least-privilege (#46) is in [SECURITY-database-accounts.md](SECURITY-database-accounts.md);
auth/RBAC/audit in [AUTH-RBAC.md](AUTH-RBAC.md).

## Secrets & credentials (#43)

**At rest.** Connection passwords are encrypted in the metadata store with **AES-256-GCM**
(`CryptoService`, key `PLATFORM_CRYPTO_KEY` — base64 32 bytes, overridden per environment). The API
never returns a stored password.

**In connector configs.** By default the platform passes the decrypted password inline when it
deploys a connector to Kafka Connect. Because Connect persists configs to its internal topic and
exposes them on the REST API, production should **externalize** them — set
`platform.connect.secrets.mode`:

| mode | connector config emits | resolved by | secret source |
|------|------------------------|-------------|---------------|
| `inline` (default) | the plaintext password | — | local/dev; relies on locked-down Connect (#45) |
| `file` | `${file:/opt/connect-secrets/<role>.properties:password}` | Connect `FileConfigProvider` | file mounted from K8s secret / Vault Agent |
| `env` | `${env:CDC_SECRET_<ROLE>_PASSWORD}` | Connect `EnvVarConfigProvider` | env injected from a secrets manager |

For `file`/`env`, configure the Connect worker with the provider, e.g.:

```properties
config.providers=file,env
config.providers.file.class=org.apache.kafka.common.config.provider.FileConfigProvider
config.providers.env.class=org.apache.kafka.common.config.provider.EnvVarConfigProvider
```

The secret value then lives only in the mounted file / worker env (sourced from your secrets
manager), never in the metadata store in plaintext, the config topic, or the REST API.

**In the repo.** Generated connector JSON and `.env` are git-ignored; only `.env.example`
(placeholders) is tracked. The legacy `generate-connectors.sh` honors `CONNECT_SECRET_MODE=file`
(emits references, no plaintext) and `MSSQL_ENCRYPT` (TLS on by default).

**Purging a leaked secret from git history.** Rotate the credential first, then rewrite history:

```bash
git filter-repo --replace-text <(echo 'OLD_SECRET==>REDACTED')   # or BFG --replace-text
git push --force-with-lease     # coordinate with the team; invalidates old clones
```

## TLS in transit (#44)

The platform is **secure by default**: generated SQL Server connectors set `database.encrypt=true`
and only add `trustServerCertificate` when a connection explicitly opts in — no blanket trust
(verified by `ConnectorConfigServiceTest.encryptDefaultsTrueAndHonoursConnectionOptions`).

| Channel | How to enable TLS |
|---------|-------------------|
| MSSQL | `encrypt=true` (default) + a trusted server cert; set connection option `trustServerCertificate` only for self-signed dev certs |
| PostgreSQL | connection option `sslmode=require` (or `verify-full` with a CA) → appended to the JDBC URL |
| Kafka / Connect | broker + Connect listeners on TLS; provide truststore via secret (see `deploy/k8s/30-kafka-connect-strimzi.yaml`, which uses the Strimzi cluster CA) |
| Control-plane API | `SERVER_SSL_ENABLED=true` + keystore, or terminate TLS at the ingress |

**Certificate management/rotation.** Store CA/keystore material in the secrets manager (or K8s
secrets / Strimzi-managed CAs), mount into the workers, and rotate by updating the secret and
rolling the pods. Strimzi auto-renews its cluster/clients CAs; for external DB certs, rotate the
truststore secret and restart connectors.

## Kafka Connect isolation (#45)

Connect's REST API (`:8083`) is unauthenticated by default — anyone who can reach it can deploy or
delete connectors and read their configs. Mitigations, all supported:

1. **Don't publish it.** Use `docker-compose.dataplane.secure.yml` (override) which removes the
   `8083:8083` host mapping — Connect is reachable only inside the Docker network, and only the
   control-plane backend (also on that network) talks to it. In K8s, Connect is a `ClusterIP`
   service, never a `LoadBalancer`/`Ingress`.
2. **Authenticate it.** Set `KAFKA_CONNECT_USER` / `KAFKA_CONNECT_PASSWORD`; the backend sends
   basic-auth on every proxied call. Put Connect behind a reverse proxy enforcing the same.
3. **Mutations go through the authenticated backend.** Operators never hit `:8083` directly — they
   use the platform API (JWT + RBAC, #55/#56), and every connector mutation is audited (#57).

Run the hardened data plane:

```bash
docker compose -f debezium-setup/docker-compose.dataplane.yml \
               -f debezium-setup/docker-compose.dataplane.secure.yml up -d
```

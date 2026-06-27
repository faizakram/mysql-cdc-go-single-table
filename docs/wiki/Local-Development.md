# Local Development

How to run the backend and frontend from source (outside Docker), run the test suites, and understand the project layout. For the container path see [Getting Started](Getting-Started.md).

## Prerequisites

- **Java 21** (Temurin/OpenJDK) + **Maven 3.9+**
- **Node 22+** + npm
- **Docker** (only if you want the data plane / monitoring while developing the app)

```bash
java -version   # 21
mvn -v          # 3.9+
node -v         # 22+
```

## Run the backend from source

The backend ships an **embedded PostgreSQL** (binaries for macOS arm64 + Linux x86), so it needs **no external database** for local dev:

```bash
cd backend
mvn spring-boot:run
```

- Starts on **http://localhost:8090**, runs Flyway migrations, boots an in-process Postgres.
- Swagger UI: http://localhost:8090/swagger-ui.html
- Metrics: http://localhost:8090/actuator/prometheus
- Stop with Ctrl-C (embedded Postgres stops with the app).

To point at an external Postgres instead:
```bash
export PLATFORM_METADATA_EMBEDDED=false
export METADATA_DB_URL=jdbc:postgresql://localhost:5432/migration_platform
export METADATA_DB_USER=postgres METADATA_DB_PASSWORD=postgres
mvn spring-boot:run
```

To talk to a real data plane while developing, start just Connect+Kafka:
```bash
docker compose -f debezium-setup/docker-compose.dataplane.yml up -d zookeeper kafka connect
export KAFKA_CONNECT_URL=http://localhost:8083
```

### Backend tests
```bash
cd backend
mvn test          # unit (surefire)
mvn verify        # unit + integration (*IT, Testcontainers — needs Docker)
```

### Package a jar
```bash
mvn -q -B -DskipTests clean package
java -jar target/migration-platform-backend-*.jar
```

## Run the frontend from source

```bash
cd frontend
npm install
npm run dev       # Vite dev server on http://localhost:5173
```

The dev server proxies `/api` → `http://localhost:8090` (see `vite.config.ts`), so with the backend running you get the full app at **:5173** with hot reload and no CORS friction.

### Frontend scripts

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server (:5173) with `/api` proxy to :8090 |
| `npm run build` | `tsc -b` + Vite production build → `dist/` (route + vendor code-splitting) |
| `npm run preview` | Serve the production build on :4173 |
| `npm run typecheck` | `tsc --noEmit` |
| `npm test` | Vitest unit/component tests (jsdom) |
| `npm run test:watch` | Vitest watch mode |
| `npm run test:e2e` | Playwright smoke (builds, serves on :4173, mocks the API — no backend needed) |

## Full-stack dev loop

```
Terminal 1:  cd backend  && mvn spring-boot:run     # :8090 (+ embedded Postgres)
Terminal 2:  cd frontend && npm run dev             # :5173 (proxy → :8090)
Browser:     http://localhost:5173   (admin / admin)
```

## Project layout

```
backend/        Spring Boot control plane (Java 21)
  src/main/java/com/migration/platform/{connection,project,job,connector,connect,
        monitoring,reconciliation,validation,planning,intelligence,quality,
        scheduling,alert,audit,auth,config,common}
  src/main/resources/{application.yml, db/migration/*.sql}
frontend/       React 18 + TS + Vite + Ant Design
  src/{pages,components,api,auth,layout,theme,hooks,test}
  e2e/          Playwright smoke
deploy/         docker-compose.full.yml, k8s/, monitoring/, sql/
debezium-setup/ Dockerfile.connect, custom-smt/, test-data/, dataplane composes
docs/           ADRs + design notes
```

### Frontend internals
- **Routing** (`App.tsx`) — lazy-loaded pages; `/users`, `/audit`, `/plugins` are ADMIN-only (redirect otherwise).
- **API client** (`src/api/client.ts`) — Axios with a base URL of `/api/v1`; a request interceptor attaches the Bearer token; a 401 interceptor clears the token and bounces to `/login`. Namespaced: `authApi`, `connectionsApi`, `projectsApi`, `jobsApi`, `schedulesApi`, `usersApi`, `alertsApi`, `auditApi`, `monitoringApi`, `schemaApi`, `reconciliationApi`, `pluginsApi`, `intelligenceApi`.
- **Auth** (`auth/AuthContext.tsx`) — loads the user via `/auth/me`, silent token refresh every ~15 min.
- **Theme** — Ant Design 5 config, light/dark + density toggles persisted to localStorage.

## Coding conventions

- Match the surrounding style; backend has **no Lombok** (plain getters/setters / records).
- Add a Flyway migration (`V<n>__*.sql`) for any schema change — never edit an applied migration; Hibernate is `validate`-only.
- New engine? Implement a `SourceConnectorStrategy` and add an `EngineCatalog` entry.
- Keep secrets out of logs and configs; use `CryptoService` / connector secret providers.
- Tests: backend unit tests are pure-logic where possible; frontend uses Vitest + a Playwright smoke. CI runs both.

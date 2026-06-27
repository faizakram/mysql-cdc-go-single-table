# Advanced platform features

Covers the capabilities added by epics #96, #100, #104, #108, #112, #116. Most expose a REST API
under `/api/v1`; verification is unit-tested where logic is pure, with live checks where a database
is reachable.

## Advanced validation & reconciliation (#96)
Beyond row-count + checksum (#49): per-table **NULL primary-key**, **duplicate-key**, bounded
**missing-row** checks and an **extra-row** indicator, rolled into a PASS/FAIL report.
- `GET /api/v1/projects/{id}/validation` — JSON report
- `GET /api/v1/projects/{id}/validation/report.csv` — downloadable CSV
- Target identifiers are resolved through the project's naming strategy (#84). Assessment logic is
  unit-tested (`ValidationLogicTest`).

## Migration simulation, rollback & recovery (#104)
- **Dry-run** (`POST /projects/{id}/dry-run`): validates connectivity, generates the plan, and
  surfaces blockers/warnings **without deploying connectors or writing to the target** (#105).
- **Checkpoint** (`POST /projects/{id}/checkpoint`): records target row counts as a marker (#106).
- **Rollback** (`POST /projects/{id}/rollback`): truncates the project's target tables to restore
  the pre-migration state — explicit + destructive (#106).
- **Resumability** (#107): inherent — the sink upserts by PK (idempotent) and connectors resume from
  committed Kafka offsets, so a restarted job continues without duplication (a fresh full reload
  needs an offset reset or a new connector name).

## AI-assisted migration intelligence (#108)
- **Cost estimate** (`GET /projects/{id}/cost-estimate`): compute + storage + first-month total from
  the plan's rows/bytes/duration and configurable rates (#110).
- **Recommendations** (`GET /projects/{id}/recommendations`): per-column target-type suggestions with
  a **rationale** and confidence; rule-based + explainable today, never auto-applied (#109).
- **Remediation** (`POST /remediation` `{"error": "..."}`): maps common failures to actionable hints
  (#111). Math + rules unit-tested (`MigrationIntelligenceTest`).

## Data quality, profiling & compliance (#112)
- **Profiling** (`GET /connections/{id}/profile?schema=&table=`): per column — null count/%, distinct,
  min/max (#113).
- **PII detection + masking** (`Pii`): detects EMAIL/PHONE/SSN/CREDIT_CARD/NAME by column name +
  value pattern, with **irreversible, shape-preserving masking** (e.g. `***@domain`, last-4) (#114).
  Unit-tested (`PiiTest`).
- **Compliance** (#115): masking + the audit log (#57) + least-privilege accounts (#46) provide the
  GDPR/residency/retention building blocks; mask PII columns during migration and audit who ran what.

## Plugin architecture & extensibility (#116)
The platform's extension points are Spring-discovered (a lightweight SPI). `GET /api/v1/plugins`
lists the loaded extensions (each source-connector strategy + each sink dialect) with versions.
Add an engine/mapper by dropping in a new `SourceConnectorStrategy` bean + an `EngineCatalog` entry —
no core edits (see `MULTI-ENGINE.md` "add a new engine").

## MongoDB source (#100)
MongoDB is a **source-only** engine (it cannot be a sink target). It captures via Debezium **change
streams** (`MongoSourceStrategy`) and flattens documents to a relational shape: top-level scalars
become columns; nested documents/arrays land as **JSON** columns on the relational target (BSON
types mapped in `TypeMappingMatrix`). Requires MongoDB as a replica set; readiness is reported via
`/cdc-readiness`. Config generation is unit-tested; live verification needs a MongoDB instance.

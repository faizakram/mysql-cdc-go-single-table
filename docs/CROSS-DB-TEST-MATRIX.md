# Cross-database integration test matrix (#15 / #59)

Real end-to-end migrations run through the platform against live containerized databases on the
`cdc-dataplane` network. Each combination = a project (connections + selected tables) started via
the API, with the migrated rows verified on the target.

## Results (verified live)

| # | Source → Target | Type | Result | Evidence |
|---|-----------------|------|--------|----------|
| 1 | **SQL Server → PostgreSQL** | heterogeneous | ✅ PASS | snapshot + live CDC + soft-delete, reconciles 0/2 (proven repeatedly) |
| 2 | **PostgreSQL → PostgreSQL** | homogeneous | ✅ PASS | `pgpg.products` = 3 (logical decoding) |
| 3 | **MySQL → PostgreSQL** | heterogeneous | ✅ PASS | `mypg.products` = 3 (binlog) — after bug #120 |
| 4 | **MongoDB → PostgreSQL** | non-relational source | ✅ PASS | `mongopg.products` = 3; `inStock`→`in_stock`, values intact — after bugs #121 + Mongo unwrap |
| 5 | **SQL Server → MySQL** | non-PG target | ✅ PASS | `warehouse.department` = 10,503 (JDBC sink, MySQL dialect) |

This exercises every dimension: homogeneous, heterogeneous relational, non-relational source, and a
non-PostgreSQL target.

## Bugs found & fixed during testing (find → fix → retry loop)

| Bug | Finding | Fix |
|-----|---------|-----|
| **#120** | MySQL **8.4** removed `SHOW MASTER STATUS`; Debezium 2.5 MySQL source snapshot fails | Pin source MySQL ≤ 8.0 (or Debezium ≥ 2.6); documented in MULTI-ENGINE.md. JDBC sink to MySQL 8.4 unaffected. |
| **#121** | MongoDB source sent SCRAM creds to an auth-less Mongo → "Exception authenticating" | `authEnabled=false` connection option omits Mongo credentials |
| (#100 finding) | JDBC sink used the relational `ExtractNewRecordState` unwrap → crashed on Mongo's document envelope | Sink picks the unwrap SMT by **source engine**: `ExtractNewDocumentState` for MongoDB, `ExtractNewRecordState` otherwise |

All three fixed, re-run, and green. 69 backend unit/integration tests pass; `EngineMatrixTest`
covers config generation for the representative pairs in CI.

## Deferred (recommended decision): Oracle & Db2

Not run live in this environment, by deliberate scoping:
- **Images are amd64-only and multi-GB**; under arm64 emulation startup is very slow and flaky.
- **Oracle's JDBC driver** has licensing constraints that block a clean automated pull, and the
  Oracle connector needs LogMiner + supplemental logging setup.

Their connector-config generation, type mapping, schema/CDC handling and risk checks are unit-tested
(`ConnectorConfigServiceTest`, `EngineMatrixTest`, `TypeMappingMatrixTest`); live verification is the
only outstanding step and needs those engines provisioned on a compatible host. The platform code
path is identical to the verified engines (pluggable `SourceConnectorStrategy` + sink dialect).

## Reproduce

Source containers (on `cdc-dataplane_default`): `pg-src` (wal_level=logical), `mysql-src` (mysql:8.0,
binlog ROW), `mongo-src` (replica set), plus `mysql-tgt` and the shared `postgres-target`. Seed a
small table per source, then create a project per combination and start it. Harness:
`scratchpad/test_matrix.py` (illustrative).

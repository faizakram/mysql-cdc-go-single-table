# Multi-engine database support (epic #76)

The platform supports migrations between **any** supported source and target engine — heterogeneous
(e.g. MySQL → PostgreSQL) and homogeneous (e.g. PostgreSQL → PostgreSQL). The engine is a
first-class choice on every connection, driven by the **engine catalog** (`EngineCatalog`).

## Compatibility matrix

Sources use a Debezium source connector; targets use the Debezium JDBC sink. Any source in the
left column can replicate to any target across the top.

| Source ↓ \ Target → | PostgreSQL | MySQL | SQL Server | Oracle | Db2 |
|---------------------|:----------:|:-----:|:----------:|:------:|:---:|
| **SQL Server**      | ✅ | ✅ | ✅ (homog.) | ✅ | ✅ |
| **PostgreSQL**      | ✅ (homog.) | ✅ | ✅ | ✅ | ✅ |
| **MySQL**           | ✅ | ✅ (homog.) | ✅ | ✅ | ✅ |
| **Oracle**          | ✅ | ✅ | ✅ | ✅ (homog.) | ✅ |
| **Db2**             | ✅ | ✅ | ✅ | ✅ | ✅ (homog.) |

Homogeneous pairs use a **type-mapping fast-path** (types pass through unchanged); heterogeneous
pairs use the `TypeMappingMatrix` (canonical-category translation). Unmappable types are flagged in
the Mapping drawer rather than silently coerced.

## Engine version compatibility (cross-DB test findings)

| Engine (as source) | Compatible with Debezium 2.5 | Note |
|--------------------|------------------------------|------|
| MySQL | **8.0** | MySQL **8.4 removed `SHOW MASTER STATUS`** → Debezium 2.5 snapshot fails (#120). Use MySQL ≤ 8.0 or upgrade the Debezium connector ≥ 2.6. The JDBC **sink** to MySQL 8.4 is unaffected. |
| MongoDB | replica set / sharded | Change streams require a replica set; set connection option `authEnabled=false` for auth-less dev instances (#121). |

## CDC style & prerequisites per source engine

| Engine | CDC style | Key prerequisites (checked by the **CDC** button / `/cdc-readiness`) |
|--------|-----------|----------------------------------------------------------------------|
| SQL Server | transaction log | DB CDC enabled (`sp_cdc_enable_db`), SQL Server Agent running |
| MySQL | binlog | `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL` |
| PostgreSQL | logical decoding | `wal_level=logical`, replication slots available |
| Oracle | LogMiner | ARCHIVELOG mode, minimal supplemental logging |
| Db2 | transaction log (ASN) | ASN capture configured by a DBA |

## Topic routing (engine-agnostic)

Source engines emit topics with different namespace depths
(`prefix.db.schema.table`, `prefix.db.table`, `prefix.schema.table`). The sink uses a generic
router — consume `<prefix>.*`, then strip all namespace segments to the final table — so the same
sink config works regardless of source engine.

## Adding a new engine (extension guide)

The design is pluggable; adding an engine touches a small, well-defined set of places and **no
existing engine code**:

1. **`DbType`** — add the enum value.
2. **`EngineCatalog`** — add one `EngineSpec` (port, driver class, JDBC URL template, can-source /
   can-sink, Debezium connector class, CDC style).
3. **Source** (if it can source): add a `SourceConnectorStrategy` implementation — a new `@Component`
   class returning the engine-specific Debezium config. Register is automatic (Spring injects all
   strategies).
4. **Sink** (if it can sink): add a `case` in `ConnectorConfigService.targetJdbcUrl` for the JDBC URL.
5. **Discovery/CDC**: add the default schema in `SchemaDiscoveryService.effectiveSchema` and a check
   branch in `CdcReadinessService`.
6. **Type mapping**: add source canonicalization + target rendering cases in `TypeMappingMatrix`.
7. **Drivers**: ensure the JDBC driver is on the control-plane classpath and the Connect image.

Unit tests in `ConnectorConfigServiceTest` and `TypeMappingMatrixTest` show the pattern; add cases
for the new engine there.

## Verification

- **Unit/config tests** (CI): `ConnectorConfigServiceTest` generates valid source + sink configs for
  representative pairs (SQL Server, MySQL, PostgreSQL sources; PostgreSQL, MySQL, Oracle targets);
  `TypeMappingMatrixTest` covers homogeneous pass-through + heterogeneous mappings.
- **Live**: SQL Server → PostgreSQL is exercised end-to-end against the data plane. Other engines
  require their respective databases; the config generation and prerequisite checks are validated by
  the test matrix above. Live verification of additional pairs needs those DB engines provisioned.

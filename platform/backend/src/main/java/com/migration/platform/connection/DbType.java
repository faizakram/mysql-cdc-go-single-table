package com.migration.platform.connection;

/**
 * Supported database engines (#76). Capabilities + connection metadata live in {@link EngineCatalog}.
 * All current engines are relational and covered by a Debezium source connector and the JDBC sink.
 */
public enum DbType {
    SQLSERVER,
    POSTGRESQL,
    MYSQL,
    ORACLE,
    DB2,
    MONGODB   // non-relational; source-only (change streams) — flattened to relational targets (#100)
}

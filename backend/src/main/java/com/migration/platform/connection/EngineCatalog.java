package com.migration.platform.connection;

import java.util.List;
import java.util.Map;

/**
 * Describes every supported database engine in one place (#76/#77): how to reach it over JDBC,
 * whether it can act as a CDC source and/or a sink target, and which Debezium source connector it
 * uses. Adding a new engine is a single entry here plus a source-connector strategy (#78).
 */
public final class EngineCatalog {

    private EngineCatalog() {}

    /** How change data is captured for a source engine — informs prerequisite checks (#80). */
    public enum CdcStyle { TRANSACTION_LOG, BINLOG, LOGICAL_DECODING, LOG_MINER, CHANGE_STREAM, NONE }

    /**
     * @param defaultPort        default TCP port
     * @param driverClass        JDBC driver class (for the control plane's own connections)
     * @param jdbcUrlTemplate    template with {host} {port} {db} placeholders
     * @param canSource          can act as a CDC source
     * @param canSink            can act as a JDBC sink target
     * @param debeziumConnector  Debezium source connector class (null if not a source)
     * @param cdcStyle           how CDC works for this engine
     */
    public record EngineSpec(
            DbType type,
            String displayName,
            int defaultPort,
            String driverClass,
            String jdbcUrlTemplate,
            boolean canSource,
            boolean canSink,
            String debeziumConnector,
            CdcStyle cdcStyle
    ) {
        public String jdbcUrl(String host, int port, String database) {
            return jdbcUrlTemplate
                    .replace("{host}", host)
                    .replace("{port}", String.valueOf(port))
                    .replace("{db}", database == null ? "" : database);
        }
    }

    private static final Map<DbType, EngineSpec> SPECS = Map.of(
            DbType.SQLSERVER, new EngineSpec(DbType.SQLSERVER, "SQL Server", 1433,
                    "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                    "jdbc:sqlserver://{host}:{port};databaseName={db}",
                    true, true, "io.debezium.connector.sqlserver.SqlServerConnector", CdcStyle.TRANSACTION_LOG),
            DbType.POSTGRESQL, new EngineSpec(DbType.POSTGRESQL, "PostgreSQL", 5432,
                    "org.postgresql.Driver",
                    "jdbc:postgresql://{host}:{port}/{db}",
                    true, true, "io.debezium.connector.postgresql.PostgresConnector", CdcStyle.LOGICAL_DECODING),
            DbType.MYSQL, new EngineSpec(DbType.MYSQL, "MySQL", 3306,
                    "com.mysql.cj.jdbc.Driver",
                    "jdbc:mysql://{host}:{port}/{db}",
                    true, true, "io.debezium.connector.mysql.MySqlConnector", CdcStyle.BINLOG),
            DbType.ORACLE, new EngineSpec(DbType.ORACLE, "Oracle", 1521,
                    "oracle.jdbc.OracleDriver",
                    "jdbc:oracle:thin:@{host}:{port}/{db}",
                    true, true, "io.debezium.connector.oracle.OracleConnector", CdcStyle.LOG_MINER),
            DbType.DB2, new EngineSpec(DbType.DB2, "Db2", 50000,
                    "com.ibm.db2.jcc.DB2Driver",
                    "jdbc:db2://{host}:{port}/{db}",
                    true, true, "io.debezium.connector.db2.Db2Connector", CdcStyle.TRANSACTION_LOG),
            DbType.MONGODB, new EngineSpec(DbType.MONGODB, "MongoDB", 27017,
                    "(native driver)",
                    "mongodb://{host}:{port}/{db}",
                    true, false, "io.debezium.connector.mongodb.MongoDbConnector", CdcStyle.CHANGE_STREAM)
    );

    public static EngineSpec spec(DbType type) {
        EngineSpec s = SPECS.get(type);
        if (s == null) throw new IllegalArgumentException("Unknown engine: " + type);
        return s;
    }

    public static List<EngineSpec> all() {
        return List.copyOf(SPECS.values());
    }

    public static List<EngineSpec> sources() {
        return SPECS.values().stream().filter(EngineSpec::canSource).toList();
    }

    public static List<EngineSpec> targets() {
        return SPECS.values().stream().filter(EngineSpec::canSink).toList();
    }

    /** Validate a source/target pairing; throws with a clear reason if unsupported (#76, #82). */
    public static void validatePair(DbType source, DbType target) {
        if (!spec(source).canSource()) {
            throw new IllegalArgumentException(spec(source).displayName() + " cannot be used as a source");
        }
        if (!spec(target).canSink()) {
            throw new IllegalArgumentException(spec(target).displayName() + " cannot be used as a target");
        }
    }

    public static boolean isHomogeneous(DbType source, DbType target) {
        return source == target;
    }
}

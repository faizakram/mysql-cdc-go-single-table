package com.migration.platform.connector;

import com.migration.platform.config.PlatformProperties;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connector.source.Db2SourceStrategy;
import com.migration.platform.connector.source.MySqlSourceStrategy;
import com.migration.platform.connector.source.OracleSourceStrategy;
import com.migration.platform.connector.source.PostgresSourceStrategy;
import com.migration.platform.connector.source.SourceConnectorStrategy;
import com.migration.platform.connector.source.SqlServerSourceStrategy;
import com.migration.platform.project.MigrationProject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Representative source→target pair matrix (#83): proves the platform generates a valid source +
 * sink connector pair for heterogeneous and homogeneous combinations.
 */
class EngineMatrixTest {

    private final ConnectorConfigService svc = new ConnectorConfigService(
            new PlatformProperties(
                    new PlatformProperties.Connect("http://connect:8083", "kafka:9092", null, null),
                    new PlatformProperties.Crypto("x"), new PlatformProperties.Cors("*"),
                    new PlatformProperties.Auth("s", 1, "a", "b"),
                    new PlatformProperties.Reconciliation("0 0 0 * * *")),
            new ConnectorSecretProperties("inline", null, null),
            List.<SourceConnectorStrategy>of(new SqlServerSourceStrategy(), new MySqlSourceStrategy(),
                    new PostgresSourceStrategy(), new OracleSourceStrategy(), new Db2SourceStrategy()));

    private DbConnection conn(DbType t, int port, String db) {
        DbConnection c = new DbConnection();
        c.setDbType(t); c.setHost("h"); c.setPort(port); c.setDatabaseName(db); c.setUsername("u");
        return c;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "MYSQL,POSTGRESQL,io.debezium.connector.mysql.MySqlConnector,jdbc:postgresql://",
            "POSTGRESQL,POSTGRESQL,io.debezium.connector.postgresql.PostgresConnector,jdbc:postgresql://",
            "SQLSERVER,MYSQL,io.debezium.connector.sqlserver.SqlServerConnector,jdbc:mysql://",
            "POSTGRESQL,ORACLE,io.debezium.connector.postgresql.PostgresConnector,jdbc:oracle:thin:@",
            "MYSQL,MYSQL,io.debezium.connector.mysql.MySqlConnector,jdbc:mysql://",
    })
    @SuppressWarnings("unchecked")
    void generatesValidSourceAndSinkForPair(DbType source, DbType target,
                                            String expectedConnector, String expectedUrlPrefix) {
        MigrationProject p = new MigrationProject();
        p.setName("Pair " + source + " to " + target);
        p.setConfig(new HashMap<>());

        Map<String, Object> srcCfg = (Map<String, Object>) svc.sourceConnector(
                p, conn(source, 1234, "srcdb"), "pw").get("config");
        Map<String, Object> sinkCfg = (Map<String, Object>) svc.sinkConnector(
                p, conn(target, 5678, "tgtdb"), "pw", source).get("config");

        assertThat(srcCfg).containsEntry("connector.class", expectedConnector);
        assertThat(sinkCfg).containsEntry("connector.class", "io.debezium.connector.jdbc.JdbcSinkConnector");
        assertThat(sinkCfg.get("connection.url").toString()).startsWith(expectedUrlPrefix);
        // Generic routing is always present regardless of pair.
        assertThat(sinkCfg).containsKey("transforms.route.regex");
    }
}

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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorConfigServiceTest {

    private static PlatformProperties platformProps() {
        return new PlatformProperties(
                new PlatformProperties.Connect("http://connect:8083", "kafka:9092", null, null),
                new PlatformProperties.Crypto("x"), new PlatformProperties.Cors("*"),
                new PlatformProperties.Auth("s", 1, "a", "b"),
                new PlatformProperties.Reconciliation("0 0 0 * * *"));
    }

    private static List<SourceConnectorStrategy> strategies() {
        return List.of(new SqlServerSourceStrategy(), new MySqlSourceStrategy(),
                new PostgresSourceStrategy(), new OracleSourceStrategy(), new Db2SourceStrategy(),
                new com.migration.platform.connector.source.MongoSourceStrategy());
    }

    private static ConnectorConfigService svc(ConnectorSecretProperties secrets) {
        return new ConnectorConfigService(platformProps(), secrets, strategies());
    }

    private final ConnectorConfigService svc = svc(new ConnectorSecretProperties("inline", null, null));

    private MigrationProject project(Map<String, Object> config) {
        MigrationProject p = new MigrationProject();
        p.setName("Employees Migration");
        p.setConfig(config);
        return p;
    }

    private DbConnection src() {
        DbConnection c = new DbConnection();
        c.setDbType(DbType.SQLSERVER);
        c.setHost("mssql"); c.setPort(1433); c.setDatabaseName("Employees"); c.setUsername("sa");
        return c;
    }

    private DbConnection tgt() {
        DbConnection c = new DbConnection();
        c.setDbType(DbType.POSTGRESQL);
        c.setHost("pg"); c.setPort(5432); c.setDatabaseName("target_db"); c.setUsername("postgres");
        return c;
    }

    private DbConnection conn(DbType type, String host, int port, String db, String user) {
        DbConnection c = new DbConnection();
        c.setDbType(type);
        c.setHost(host); c.setPort(port); c.setDatabaseName(db); c.setUsername(user);
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void mysqlSourceUsesBinlogConnectorAndServerId() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> cfg = (Map<String, Object>) svc.sourceConnector(
                p, conn(DbType.MYSQL, "mysql", 3306, "shop", "repl"), "pw").get("config");
        assertThat(cfg).containsEntry("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        assertThat(cfg).containsEntry("database.include.list", "shop");
        assertThat(cfg).containsKey("database.server.id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void postgresSourceUsesLogicalDecodingSlot() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> cfg = (Map<String, Object>) svc.sourceConnector(
                p, conn(DbType.POSTGRESQL, "pg", 5432, "shop", "repl"), "pw").get("config");
        assertThat(cfg).containsEntry("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        assertThat(cfg).containsEntry("plugin.name", "pgoutput");
        assertThat(cfg).containsKey("slot.name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinkUrlMatchesTargetDialect_homogeneousMysql() {
        // Homogeneous MySQL -> MySQL: sink URL is MySQL and routing stays generic.
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(
                p, conn(DbType.MYSQL, "mysqltgt", 3306, "warehouse", "app"), "pw", DbType.MYSQL).get("config");
        assertThat(sink.get("connection.url").toString()).startsWith("jdbc:mysql://mysqltgt:3306/warehouse");
        // MySQL has no separate schema → table.name.format is unqualified.
        assertThat(sink).containsEntry("table.name.format", "${topic}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinkUrlMatchesTargetDialect_oracle() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(
                p, conn(DbType.ORACLE, "ora", 1521, "ORCLPDB1", "app"), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink.get("connection.url").toString()).isEqualTo("jdbc:oracle:thin:@ora:1521/ORCLPDB1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inlineModePutsPlaintextPasswordIntoConfig() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> src = (Map<String, Object>) svc.sourceConnector(p, src(), "s3cr3t").get("config");
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "t0ps3cret", DbType.SQLSERVER).get("config");
        assertThat(src).containsEntry("database.password", "s3cr3t");
        assertThat(sink).containsEntry("connection.password", "t0ps3cret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileModeEmitsProviderReferenceNotPlaintext() {
        // #43: externalized secrets — the plaintext must NOT appear; a provider reference does.
        ConnectorConfigService externalized = svc(new ConnectorSecretProperties("file", "/opt/connect-secrets", null));
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> src = (Map<String, Object>) externalized.sourceConnector(p, src(), "s3cr3t").get("config");
        Map<String, Object> sink = (Map<String, Object>) externalized.sinkConnector(p, tgt(), "s3cr3t", DbType.SQLSERVER).get("config");
        assertThat(src.get("database.password")).isEqualTo("${file:/opt/connect-secrets/source.properties:password}");
        assertThat(sink.get("connection.password")).isEqualTo("${file:/opt/connect-secrets/sink.properties:password}");
        assertThat(src.toString()).doesNotContain("s3cr3t");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preserveIsDefaultAndOmitsRenameSmtAndQuotesIdentifiers() {
        // #84: default must NOT rename, and must quote identifiers so source case survives.
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink.get("transforms").toString()).doesNotContain("caseKey", "caseValue", "snakeCase");
        assertThat(sink).containsEntry("quote.identifiers", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void snakeCaseStrategyAddsCaseSmtAndUnquotedIdentifiers() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("namingStrategy", "snake_case");
        MigrationProject p = project(cfg);
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink.get("transforms").toString()).contains("caseKey", "caseValue");
        assertThat(sink).containsEntry("transforms.caseValue.strategy", "snake_case");
        assertThat(sink).containsEntry("quote.identifiers", "false");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upperCaseStrategyPassesStrategyToSmtAndQuotes() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("namingStrategy", "UPPER_CASE");
        MigrationProject p = project(cfg);
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink).containsEntry("transforms.caseKey.strategy", "upper_case");
        assertThat(sink).containsEntry("quote.identifiers", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mongoSourceUsesChangeStreamsConnector() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> cfg = (Map<String, Object>) svc.sourceConnector(
                p, conn(DbType.MONGODB, "mongo", 27017, "shop", "repl"), "pw").get("config");
        assertThat(cfg).containsEntry("connector.class", "io.debezium.connector.mongodb.MongoDbConnector");
        assertThat(cfg).containsKey("mongodb.connection.string");
        assertThat(cfg).containsEntry("database.include.list", "shop");
    }

    @Test
    void connectorNamesAreSanitisedFromProjectName() {
        MigrationProject p = project(new HashMap<>());
        assertThat(svc.sourceName(p)).isEqualTo("employees-migration-source");
        assertThat(svc.sinkName(p)).isEqualTo("employees-migration-sink");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hardDeleteEnablesTombstonesAndSinkDeleteAndOmitsRewrite() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("deleteStrategy", "HARD");
        MigrationProject p = project(cfg);

        Map<String, Object> source = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");

        assertThat(source).containsEntry("tombstones.on.delete", "true");
        assertThat(sink).containsEntry("delete.enabled", "true");
        assertThat(sink.get("transforms").toString()).doesNotContain("renameDeleted");
        // DELETE events must pass through (default "drop" would swallow them → row never removed).
        assertThat(sink).containsEntry("transforms.unwrap.delete.handling.mode", "none");
        assertThat(sink).containsEntry("transforms.unwrap.drop.tombstones", "false");
        // #161: collapse per-key events in a batch so insert+delete of the same row under load
        // resolves to the delete (no phantom rows on the target).
        assertThat(sink).containsEntry("use.reduction.buffer", "true");
        // #176: poison rows go to a DLQ and don't stop the task (resilient default).
        assertThat(sink).containsEntry("errors.tolerance", "all");
        assertThat(sink).containsEntry("errors.deadletterqueue.topic.name", "employees-migration-sink-dlq");
        assertThat(sink).containsEntry("errors.log.enable", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void strictErrorToleranceDisablesDlqAndFailsFast() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("errorTolerance", "none");
        MigrationProject p = project(cfg);
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink).containsEntry("errors.tolerance", "none");
        assertThat(sink).doesNotContainKey("errors.deadletterqueue.topic.name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void softDeleteDisablesTombstonesAndRewritesToCdcDeleted() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("deleteStrategy", "SOFT");
        MigrationProject p = project(cfg);

        Map<String, Object> source = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");

        assertThat(source).containsEntry("tombstones.on.delete", "false");
        assertThat(sink).containsEntry("delete.enabled", "false");
        assertThat(sink.get("transforms").toString()).contains("renameDeleted", "castDeleted");
        assertThat(sink).containsEntry("transforms.renameDeleted.renames", "__deleted:__cdc_deleted");
        // Reduction buffer is a HARD-delete concern only; SOFT delete never issues target deletes.
        assertThat(sink).doesNotContainKey("use.reduction.buffer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinkRoutingIsEngineAgnostic() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("topicPrefix", "mssql");
        MigrationProject p = project(cfg);

        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        // Generic routing: consume everything under the prefix, strip all namespace segments to the table.
        assertThat(sink).containsEntry("topics.regex", "mssql\\..*");
        assertThat(sink).containsEntry("transforms.route.regex", "mssql\\.(?:[^.]+\\.)+([^.]+)");
        assertThat(sink).containsEntry("transforms.route.replacement", "$1");
        // typeConversion is always present; naming SMTs are added only for non-preserve strategies (#84).
        assertThat(sink.get("transforms").toString()).contains("route", "unwrap", "typeConversion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void encryptDefaultsTrueAndHonoursConnectionOptions() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> srcCfgDefault = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        assertThat(srcCfgDefault).containsEntry("database.encrypt", "true");

        DbConnection insecure = src();
        insecure.setOptions(Map.of("encrypt", false, "trustServerCertificate", true));
        Map<String, Object> srcCfg = (Map<String, Object>) svc.sourceConnector(p, insecure, "pw").get("config");
        assertThat(srcCfg).containsEntry("database.encrypt", "false");
        assertThat(srcCfg).containsEntry("database.trustServerCertificate", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotTuningAndSchemaEvolutionComeFromConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("snapshotMaxThreads", 4);
        cfg.put("snapshotFetchSize", 5000);
        cfg.put("schemaEvolution", "none");
        MigrationProject p = project(cfg);

        Map<String, Object> source = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        assertThat(source).containsEntry("snapshot.max.threads", "4").containsEntry("snapshot.fetch.size", "5000");

        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink).containsEntry("schema.evolution", "none");
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultsSchemaEvolutionBasicAndSingleSnapshotThread() {
        MigrationProject p = project(new HashMap<>());
        Map<String, Object> source = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        assertThat(source).containsEntry("snapshot.max.threads", "1");
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink).containsEntry("schema.evolution", "basic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void uuidAndJsonColumnsFlowIntoTypeConversion() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("uuidColumns", "user_id,session_id");
        cfg.put("jsonColumns", "metadata");
        MigrationProject p = project(cfg);

        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", DbType.SQLSERVER).get("config");
        assertThat(sink).containsEntry("transforms.typeConversion.uuid.columns", "user_id,session_id");
        assertThat(sink).containsEntry("transforms.typeConversion.json.columns", "metadata");
    }
}

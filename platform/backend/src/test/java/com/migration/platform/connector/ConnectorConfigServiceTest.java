package com.migration.platform.connector;

import com.migration.platform.config.PlatformProperties;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.project.MigrationProject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorConfigServiceTest {

    private final ConnectorConfigService svc = new ConnectorConfigService(
            new PlatformProperties(
                    new PlatformProperties.Connect("http://connect:8083", "kafka:9092", null, null),
                    new PlatformProperties.Crypto("x"), new PlatformProperties.Cors("*"),
                    new PlatformProperties.Auth("s", 1, "a", "b"),
                    new PlatformProperties.Reconciliation("0 0 0 * * *")));

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
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", "Employees").get("config");

        assertThat(source).containsEntry("tombstones.on.delete", "true");
        assertThat(sink).containsEntry("delete.enabled", "true");
        assertThat(sink.get("transforms").toString()).doesNotContain("renameDeleted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void softDeleteDisablesTombstonesAndRewritesToCdcDeleted() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("deleteStrategy", "SOFT");
        MigrationProject p = project(cfg);

        Map<String, Object> source = (Map<String, Object>) svc.sourceConnector(p, src(), "pw").get("config");
        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", "Employees").get("config");

        assertThat(source).containsEntry("tombstones.on.delete", "false");
        assertThat(sink).containsEntry("delete.enabled", "false");
        assertThat(sink.get("transforms").toString()).contains("renameDeleted", "castDeleted");
        assertThat(sink).containsEntry("transforms.renameDeleted.renames", "__deleted:__cdc_deleted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinkTopicsRegexMatchesSourcePrefixAndDatabase() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("topicPrefix", "mssql");
        MigrationProject p = project(cfg);

        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", "Employees").get("config");
        assertThat(sink).containsEntry("topics.regex", "mssql\\.Employees\\.dbo\\.(.*)");
        assertThat(sink.get("transforms").toString()).contains("snakeCaseKey", "snakeCaseValue", "typeConversion");
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
    void uuidAndJsonColumnsFlowIntoTypeConversion() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("uuidColumns", "user_id,session_id");
        cfg.put("jsonColumns", "metadata");
        MigrationProject p = project(cfg);

        Map<String, Object> sink = (Map<String, Object>) svc.sinkConnector(p, tgt(), "pw", "Employees").get("config");
        assertThat(sink).containsEntry("transforms.typeConversion.uuid.columns", "user_id,session_id");
        assertThat(sink).containsEntry("transforms.typeConversion.json.columns", "metadata");
    }
}

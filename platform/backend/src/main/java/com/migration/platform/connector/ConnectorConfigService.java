package com.migration.platform.connector;

import com.migration.platform.config.PlatformProperties;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.project.MigrationProject;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates Debezium source (SQL Server) and JDBC sink (PostgreSQL) connector configs from a
 * project + its connections. Centralizes the logic that used to live in hand-edited JSON files,
 * and makes delete semantics consistent per {@link DeleteStrategy} (issue #25).
 */
@Service
public class ConnectorConfigService {

    private final PlatformProperties props;

    public ConnectorConfigService(PlatformProperties props) {
        this.props = props;
    }

    public String sourceName(MigrationProject p) {
        return MigrationConfig.sanitize(p.getName()) + "-source";
    }

    public String sinkName(MigrationProject p) {
        return MigrationConfig.sanitize(p.getName()) + "-sink";
    }

    /** Full payload accepted by {@code POST /connectors}. */
    public Map<String, Object> sourceConnector(MigrationProject p, DbConnection src, String srcPassword) {
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        boolean hard = mc.deleteStrategy() == DeleteStrategy.HARD;

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
        cfg.put("tasks.max", String.valueOf(mc.tasksMax()));
        cfg.put("database.hostname", src.getHost());
        cfg.put("database.port", String.valueOf(src.getPort()));
        cfg.put("database.user", src.getUsername());
        cfg.put("database.password", srcPassword);
        cfg.put("database.names", src.getDatabaseName());
        // TLS driven by the connection (#44): secure by default; opt out only for dev.
        boolean encrypt = optBool(src.getOptions(), "encrypt", true);
        cfg.put("database.encrypt", String.valueOf(encrypt));
        if (optBool(src.getOptions(), "trustServerCertificate", false)) {
            cfg.put("database.trustServerCertificate", "true");
        }
        cfg.put("topic.prefix", mc.topicPrefix());
        cfg.put("table.include.list", mc.tableIncludeList());
        cfg.put("schema.history.internal.kafka.bootstrap.servers", props.connect().kafkaBootstrap());
        cfg.put("schema.history.internal.kafka.topic", "schema-changes." + mc.topicPrefix());
        cfg.put("snapshot.mode", mc.snapshotMode());
        cfg.put("snapshot.isolation.mode", "read_committed");
        // Large-table snapshot tuning (#27): parallel workers + fetch size.
        cfg.put("snapshot.max.threads", String.valueOf(mc.snapshotMaxThreads()));
        cfg.put("snapshot.fetch.size", String.valueOf(mc.snapshotFetchSize()));
        cfg.put("decimal.handling.mode", "precise");
        cfg.put("time.precision.mode", "adaptive_time_microseconds");
        cfg.put("tombstones.on.delete", String.valueOf(hard));

        return payload(sourceName(p), cfg);
    }

    public Map<String, Object> sinkConnector(MigrationProject p, DbConnection tgt, String tgtPassword,
                                             String sourceDbName) {
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        boolean hard = mc.deleteStrategy() == DeleteStrategy.HARD;
        String prefix = mc.topicPrefix();

        Object sslmode = tgt.getOptions() == null ? null : tgt.getOptions().get("sslmode");
        String sslParam = (sslmode != null && !sslmode.toString().isBlank())
                ? "&sslmode=" + sslmode : "";

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("connector.class", "io.debezium.connector.jdbc.JdbcSinkConnector");
        cfg.put("tasks.max", String.valueOf(mc.tasksMax()));
        cfg.put("connection.url", "jdbc:postgresql://" + tgt.getHost() + ":" + tgt.getPort()
                + "/" + tgt.getDatabaseName() + "?currentSchema=" + mc.targetSchema() + sslParam);
        cfg.put("connection.username", tgt.getUsername());
        cfg.put("connection.password", tgtPassword);

        // <prefix>.<sourceDb>.dbo.<table>  ->  <table>
        String topicsRegex = prefix + "\\." + sourceDbName + "\\.dbo\\.(.*)";
        cfg.put("topics.regex", topicsRegex);

        // Transform chain. Soft delete adds the rewrite + rename + cast; hard delete skips them.
        StringBuilder transforms = new StringBuilder("route,unwrap");
        cfg.put("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter");
        cfg.put("transforms.route.regex", topicsRegex);
        cfg.put("transforms.route.replacement", "$1");
        cfg.put("transforms.unwrap.type", "io.debezium.transforms.ExtractNewRecordState");

        if (hard) {
            cfg.put("transforms.unwrap.drop.tombstones", "false");
        } else {
            cfg.put("transforms.unwrap.delete.handling.mode", "rewrite");
            transforms.append(",renameDeleted,castDeleted");
            cfg.put("transforms.renameDeleted.type", "org.apache.kafka.connect.transforms.ReplaceField$Value");
            cfg.put("transforms.renameDeleted.renames", "__deleted:__cdc_deleted");
            cfg.put("transforms.castDeleted.type", "org.apache.kafka.connect.transforms.Cast$Value");
            cfg.put("transforms.castDeleted.spec", "__cdc_deleted:boolean");
        }

        transforms.append(",snakeCaseKey,snakeCaseValue,typeConversion");
        cfg.put("transforms.snakeCaseKey.type", "com.debezium.transforms.SnakeCaseTransform$Key");
        cfg.put("transforms.snakeCaseValue.type", "com.debezium.transforms.SnakeCaseTransform$Value");
        cfg.put("transforms.typeConversion.type", "com.debezium.transforms.TypeConversionTransform");
        if (!mc.uuidColumns().isEmpty()) {
            cfg.put("transforms.typeConversion.uuid.columns", String.join(",", mc.uuidColumns()));
        }
        if (!mc.jsonColumns().isEmpty()) {
            cfg.put("transforms.typeConversion.json.columns", String.join(",", mc.jsonColumns()));
        }
        cfg.put("transforms", transforms.toString());

        cfg.put("table.name.format", mc.targetSchema() + ".${topic}");
        cfg.put("insert.mode", "upsert");
        cfg.put("delete.enabled", String.valueOf(hard));     // consistent with the source tombstone setting
        cfg.put("primary.key.mode", "record_key");
        cfg.put("schema.evolution", mc.schemaEvolution());
        cfg.put("quote.identifiers", "false");

        return payload(sinkName(p), cfg);
    }

    private Map<String, Object> payload(String name, Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("config", cfg);
        return out;
    }

    private boolean optBool(Map<String, Object> options, String key, boolean def) {
        if (options == null) return def;
        Object v = options.get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }
}

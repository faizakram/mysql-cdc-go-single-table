package com.migration.platform.connector;

import com.migration.platform.config.PlatformProperties;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connector.source.SourceConnectorStrategy;
import com.migration.platform.connector.source.SourceContext;
import com.migration.platform.project.MigrationProject;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates Debezium source + JDBC sink connector configs for a project (#25), now engine-agnostic
 * (#76): the source is built by a per-engine {@link SourceConnectorStrategy}, and the sink targets
 * any supported dialect. Topic routing is generic, so the sink works regardless of how many
 * namespace segments the source engine emits.
 */
@Service
public class ConnectorConfigService {

    private final PlatformProperties props;
    private final ConnectorSecretProperties secrets;
    private final Map<DbType, SourceConnectorStrategy> sources = new EnumMap<>(DbType.class);

    public ConnectorConfigService(PlatformProperties props, ConnectorSecretProperties secrets,
                                  List<SourceConnectorStrategy> strategies) {
        this.props = props;
        this.secrets = secrets;
        for (SourceConnectorStrategy s : strategies) this.sources.put(s.engine(), s);
    }

    public String sourceName(MigrationProject p) { return MigrationConfig.sanitize(p.getName()) + "-source"; }
    public String sinkName(MigrationProject p) { return MigrationConfig.sanitize(p.getName()) + "-sink"; }

    public Map<String, Object> sourceConnector(MigrationProject p, DbConnection src, String srcPassword) {
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        boolean hard = mc.deleteStrategy() == DeleteStrategy.HARD;

        SourceConnectorStrategy strategy = sources.get(src.getDbType());
        if (strategy == null) {
            throw new IllegalArgumentException("No source connector support for engine " + src.getDbType());
        }
        SourceContext ctx = new SourceContext(src, mc, secrets.passwordValue("source", srcPassword),
                props.connect().kafkaBootstrap(), hard);
        return payload(sourceName(p), strategy.buildConfig(ctx));
    }

    public Map<String, Object> sinkConnector(MigrationProject p, DbConnection tgt, String tgtPassword,
                                             DbType sourceEngine) {
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        boolean hard = mc.deleteStrategy() == DeleteStrategy.HARD;
        String prefix = mc.topicPrefix();
        boolean schemaQualified = tgt.getDbType() != DbType.MYSQL;   // MySQL has no separate schema

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("connector.class", "io.debezium.connector.jdbc.JdbcSinkConnector");
        cfg.put("tasks.max", String.valueOf(mc.tasksMax()));
        cfg.put("connection.url", targetJdbcUrl(tgt, mc.targetSchema()));
        cfg.put("connection.username", tgt.getUsername());
        cfg.put("connection.password", secrets.passwordValue("sink", tgtPassword));

        // Generic routing (#76): consume everything under the prefix and strip all namespace
        // segments down to the final table name — works for any source engine's topic depth
        // (sqlserver: prefix.db.schema.table, mysql: prefix.db.table, postgres: prefix.schema.table).
        String topicsRegex = prefix + "\\..*";
        String routeRegex = prefix + "\\.(?:[^.]+\\.)+([^.]+)";
        cfg.put("topics.regex", topicsRegex);

        StringBuilder transforms = new StringBuilder("route,unwrap");
        cfg.put("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter");
        cfg.put("transforms.route.regex", routeRegex);
        cfg.put("transforms.route.replacement", "$1");
        // MongoDB emits a document envelope and needs the Mongo-specific unwrap SMT; relational
        // engines use ExtractNewRecordState (#100 cross-DB test finding).
        cfg.put("transforms.unwrap.type", sourceEngine == DbType.MONGODB
                ? "io.debezium.connector.mongodb.transforms.ExtractNewDocumentState"
                : "io.debezium.transforms.ExtractNewRecordState");

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

        // Naming strategy (#84): PRESERVE keeps source names exactly (no rename SMT); the others
        // apply a deterministic case transform via the custom SMT.
        NamingStrategy ns = mc.namingStrategy();
        if (!ns.isPreserve()) {
            transforms.append(",caseKey,caseValue");
            cfg.put("transforms.caseKey.type", "com.debezium.transforms.SnakeCaseTransform$Key");
            cfg.put("transforms.caseValue.type", "com.debezium.transforms.SnakeCaseTransform$Value");
            cfg.put("transforms.caseKey.strategy", ns.smtValue());
            cfg.put("transforms.caseValue.strategy", ns.smtValue());
        }
        transforms.append(",typeConversion");
        cfg.put("transforms.typeConversion.type", "com.debezium.transforms.TypeConversionTransform");
        if (!mc.uuidColumns().isEmpty()) {
            cfg.put("transforms.typeConversion.uuid.columns", String.join(",", mc.uuidColumns()));
        }
        if (!mc.jsonColumns().isEmpty()) {
            cfg.put("transforms.typeConversion.json.columns", String.join(",", mc.jsonColumns()));
        }
        cfg.put("transforms", transforms.toString());

        cfg.put("table.name.format", schemaQualified ? mc.targetSchema() + ".${topic}" : "${topic}");
        cfg.put("insert.mode", "upsert");
        cfg.put("delete.enabled", String.valueOf(hard));
        cfg.put("primary.key.mode", "record_key");
        cfg.put("schema.evolution", mc.schemaEvolution());
        // Quote identifiers unless the names are already lowercase snake_case — so PRESERVE / camel /
        // Pascal / UPPER case survive exactly on the target (e.g. PostgreSQL would otherwise fold them).
        cfg.put("quote.identifiers", String.valueOf(ns != NamingStrategy.SNAKE_CASE));

        return payload(sinkName(p), cfg);
    }

    /** Build the sink JDBC URL for the target dialect (#79). */
    private String targetJdbcUrl(DbConnection tgt, String targetSchema) {
        String host = tgt.getHost();
        int port = tgt.getPort();
        String db = tgt.getDatabaseName();
        Object sslmode = tgt.getOptions() == null ? null : tgt.getOptions().get("sslmode");
        String sslParam = (sslmode != null && !sslmode.toString().isBlank()) ? "&sslmode=" + sslmode : "";
        return switch (tgt.getDbType()) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db
                    + "?currentSchema=" + targetSchema + sslParam;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db;
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + "/" + db;
            case DB2 -> "jdbc:db2://" + host + ":" + port + "/" + db;
            case MONGODB -> throw new IllegalArgumentException("MongoDB is source-only; choose a relational target");
        };
    }

    private Map<String, Object> payload(String name, Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("config", cfg);
        return out;
    }
}

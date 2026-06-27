package com.migration.platform.connector.source;

import com.migration.platform.connection.DbConnection;
import com.migration.platform.connector.MigrationConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a {@link SourceConnectorStrategy} needs to build an engine's source connector config.
 * The password is already resolved to its final value/reference (inline or ${...}) by the service,
 * so strategies never touch secrets directly (#43).
 */
public record SourceContext(
        DbConnection src,
        MigrationConfig mc,
        String passwordRef,
        String kafkaBootstrap,
        boolean hardDelete
) {
    /** A config map pre-seeded with the connection/host/credentials keys common to all engines. */
    public Map<String, Object> baseConfig(String connectorClass) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("connector.class", connectorClass);
        cfg.put("tasks.max", String.valueOf(mc.tasksMax()));
        cfg.put("database.hostname", src.getHost());
        cfg.put("database.port", String.valueOf(src.getPort()));
        cfg.put("database.user", src.getUsername());
        cfg.put("database.password", passwordRef);
        cfg.put("topic.prefix", mc.topicPrefix());
        cfg.put("snapshot.mode", mc.snapshotMode());
        cfg.put("tombstones.on.delete", String.valueOf(hardDelete));
        return cfg;
    }

    public void schemaHistory(Map<String, Object> cfg) {
        cfg.put("schema.history.internal.kafka.bootstrap.servers", kafkaBootstrap);
        cfg.put("schema.history.internal.kafka.topic", "schema-changes." + mc.topicPrefix());
    }

    public boolean optBool(String key, boolean def) {
        Map<String, Object> o = src.getOptions();
        if (o == null) return def;
        Object v = o.get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    public String optStr(String key, String def) {
        Map<String, Object> o = src.getOptions();
        if (o == null) return def;
        Object v = o.get(key);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }
}

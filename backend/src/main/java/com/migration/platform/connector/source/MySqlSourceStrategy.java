package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** MySQL source (binlog CDC). Requires a unique numeric {@code database.server.id} per connector. */
@Component
public class MySqlSourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.MYSQL; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.mysql.MySqlConnector");
        cfg.put("database.include.list", ctx.src().getDatabaseName());
        cfg.put("table.include.list", ctx.mc().tableIncludeList());
        // server.id must be unique across the MySQL replication topology; derive a stable value.
        String serverId = ctx.optStr("serverId", null);
        if (serverId == null) {
            int id = ctx.src().getId() != null ? Math.abs(ctx.src().getId().hashCode() % 9_000_000) + 1000 : 5400;
            serverId = String.valueOf(id);
        }
        cfg.put("database.server.id", serverId);
        ctx.schemaHistory(cfg);
        cfg.put("snapshot.fetch.size", String.valueOf(ctx.mc().snapshotFetchSize()));
        cfg.put("decimal.handling.mode", "precise");
        return cfg;
    }
}

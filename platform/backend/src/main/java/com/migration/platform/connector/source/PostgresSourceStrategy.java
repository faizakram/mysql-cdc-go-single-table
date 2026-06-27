package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import com.migration.platform.connector.MigrationConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

/** PostgreSQL source (logical decoding via pgoutput). No schema-history topic; uses a replication slot. */
@Component
public class PostgresSourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.POSTGRESQL; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.postgresql.PostgresConnector");
        cfg.put("database.dbname", ctx.src().getDatabaseName());
        cfg.put("plugin.name", ctx.optStr("pluginName", "pgoutput"));
        // Slot/publication names must be valid Postgres identifiers (lowercase, no dashes).
        String slot = ("dbz_" + MigrationConfig.sanitize(ctx.mc().topicPrefix())).replace('-', '_');
        cfg.put("slot.name", ctx.optStr("slotName", slot));
        cfg.put("publication.name", ctx.optStr("publicationName", slot + "_pub"));
        cfg.put("publication.autocreate.mode", "filtered");
        cfg.put("table.include.list", ctx.mc().tableIncludeList());
        String sslmode = ctx.optStr("sslmode", null);
        if (sslmode != null) cfg.put("database.sslmode", sslmode);
        cfg.put("decimal.handling.mode", "precise");
        return cfg;
    }
}

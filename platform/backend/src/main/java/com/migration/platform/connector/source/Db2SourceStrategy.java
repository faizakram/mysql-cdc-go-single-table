package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Db2 (LUW) source (ASN transaction-log CDC). Uses a schema-history topic. */
@Component
public class Db2SourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.DB2; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.db2.Db2Connector");
        cfg.put("database.dbname", ctx.src().getDatabaseName());
        cfg.put("table.include.list", ctx.mc().tableIncludeList());
        ctx.schemaHistory(cfg);
        cfg.put("snapshot.fetch.size", String.valueOf(ctx.mc().snapshotFetchSize()));
        cfg.put("decimal.handling.mode", "precise");
        return cfg;
    }
}

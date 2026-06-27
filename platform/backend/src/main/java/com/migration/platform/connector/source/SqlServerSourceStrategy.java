package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** SQL Server source (transaction-log CDC). Preserves the platform's original config exactly. */
@Component
public class SqlServerSourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.SQLSERVER; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.sqlserver.SqlServerConnector");
        cfg.put("database.names", ctx.src().getDatabaseName());
        cfg.put("database.encrypt", String.valueOf(ctx.optBool("encrypt", true)));
        if (ctx.optBool("trustServerCertificate", false)) {
            cfg.put("database.trustServerCertificate", "true");
        }
        cfg.put("table.include.list", ctx.mc().tableIncludeList());
        ctx.schemaHistory(cfg);
        cfg.put("snapshot.isolation.mode", "read_committed");
        cfg.put("snapshot.max.threads", String.valueOf(ctx.mc().snapshotMaxThreads()));
        cfg.put("snapshot.fetch.size", String.valueOf(ctx.mc().snapshotFetchSize()));
        cfg.put("decimal.handling.mode", "precise");
        cfg.put("time.precision.mode", "adaptive_time_microseconds");
        return cfg;
    }
}

package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MongoDB source via Debezium change streams (#100). Documents are flattened to a relational shape
 * downstream (ExtractNewDocumentState + the type/JSON SMTs): top-level scalars become columns and
 * nested documents/arrays land as JSON columns on the relational target.
 */
@Component
public class MongoSourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.MONGODB; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.mongodb.MongoDbConnector");
        // Mongo uses a connection string, not host/port JDBC; provide both forms.
        cfg.put("mongodb.connection.string",
                ctx.optStr("connectionString",
                        "mongodb://" + ctx.src().getHost() + ":" + ctx.src().getPort()));
        // Auth is optional (#121): omit credentials when the connection has authEnabled=false
        // (e.g. a dev MongoDB with no auth), otherwise SCRAM auth would fail.
        if (ctx.optBool("authEnabled", true)) {
            cfg.put("mongodb.user", ctx.src().getUsername());
            cfg.put("mongodb.password", ctx.passwordRef());
        }
        cfg.put("database.include.list", ctx.src().getDatabaseName());
        cfg.put("collection.include.list", ctx.mc().tableIncludeList());
        cfg.put("capture.mode", ctx.optStr("captureMode", "change_streams_update_full"));
        return cfg;
    }
}

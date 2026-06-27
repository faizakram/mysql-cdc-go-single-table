package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Oracle source (LogMiner CDC). Uses a schema-history topic; PDB name optional for CDB setups. */
@Component
public class OracleSourceStrategy implements SourceConnectorStrategy {

    @Override public DbType engine() { return DbType.ORACLE; }

    @Override
    public Map<String, Object> buildConfig(SourceContext ctx) {
        Map<String, Object> cfg = ctx.baseConfig("io.debezium.connector.oracle.OracleConnector");
        cfg.put("database.dbname", ctx.src().getDatabaseName());
        String pdb = ctx.optStr("pdbName", null);
        if (pdb != null) cfg.put("database.pdb.name", pdb);
        cfg.put("table.include.list", ctx.mc().tableIncludeList());
        ctx.schemaHistory(cfg);
        cfg.put("log.mining.strategy", ctx.optStr("logMiningStrategy", "online_catalog"));
        cfg.put("snapshot.fetch.size", String.valueOf(ctx.mc().snapshotFetchSize()));
        cfg.put("decimal.handling.mode", "precise");
        return cfg;
    }
}

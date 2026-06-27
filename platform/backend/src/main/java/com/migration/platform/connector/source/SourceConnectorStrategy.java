package com.migration.platform.connector.source;

import com.migration.platform.connection.DbType;

import java.util.Map;

/**
 * Builds the engine-specific Debezium source-connector config (#78). One implementation per engine;
 * adding an engine means adding a new strategy — no edits to existing ones. The shared
 * delete/SMT/sink wiring stays engine-agnostic in ConnectorConfigService.
 */
public interface SourceConnectorStrategy {

    DbType engine();

    /** The connector {@code config} block (without the {name, config} envelope). */
    Map<String, Object> buildConfig(SourceContext ctx);
}

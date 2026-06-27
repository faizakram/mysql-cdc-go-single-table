package com.migration.platform.plugin;

import com.migration.platform.connection.EngineCatalog;
import com.migration.platform.connector.source.SourceConnectorStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extensibility registry (#116). The platform's extension points are discovered at runtime via
 * Spring (a lightweight SPI): each {@link SourceConnectorStrategy} bean is a source-engine plugin,
 * and the {@link EngineCatalog} declares engine capabilities. This registry makes the loaded
 * extensions observable + versioned, so operators can see exactly what the deployment supports and
 * contributors can add capabilities without editing core code.
 */
@Component
public class PluginRegistry {

    /** SPI contract for an extension. Implemented by source strategies (and future mappers/validators). */
    public interface Plugin {
        String id();
        String kind();      // SOURCE_CONNECTOR | TYPE_MAPPER | VALIDATOR | TRANSFORM
        String version();
    }

    public record PluginInfo(String id, String kind, String version, String detail) {}

    private final List<SourceConnectorStrategy> sourceStrategies;

    public PluginRegistry(List<SourceConnectorStrategy> sourceStrategies) {
        this.sourceStrategies = sourceStrategies;
    }

    public List<PluginInfo> list() {
        List<PluginInfo> out = new ArrayList<>();
        for (SourceConnectorStrategy s : sourceStrategies) {
            var spec = EngineCatalog.spec(s.engine());
            out.add(new PluginInfo("source:" + s.engine(), "SOURCE_CONNECTOR", "1.0",
                    spec.displayName() + " via " + spec.debeziumConnector()));
        }
        // Type-mapper + sink-dialect support is built-in for every catalog engine.
        for (var spec : EngineCatalog.all()) {
            if (spec.canSink()) {
                out.add(new PluginInfo("sink:" + spec.type(), "SINK_DIALECT", "1.0", spec.displayName() + " JDBC sink"));
            }
        }
        return out;
    }
}

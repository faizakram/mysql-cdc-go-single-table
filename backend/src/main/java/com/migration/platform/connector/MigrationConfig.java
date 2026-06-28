package com.migration.platform.connector;

import java.util.List;
import java.util.Map;

/**
 * Typed view over the free-form {@code project.config} JSONB, with sensible defaults.
 * These are the knobs the UI migration/CDC wizard (#38) edits.
 */
public record MigrationConfig(
        String topicPrefix,
        String tableIncludeList,
        String snapshotMode,
        DeleteStrategy deleteStrategy,
        String targetSchema,
        List<String> uuidColumns,
        List<String> jsonColumns,
        int tasksMax,
        String schemaEvolution,   // sink DDL evolution: basic | none (#26)
        int snapshotMaxThreads,   // parallel snapshot workers (#27)
        int snapshotFetchSize,    // snapshot row fetch size (#27)
        NamingStrategy namingStrategy,  // identifier naming on the target (#84); default PRESERVE
        String errorTolerance,    // sink errors: all (skip bad rows to DLQ, keep streaming) | none (fail fast) (#176)
        int sinkBatchSize         // JDBC sink rows per batch — higher = faster bulk apply (#215)
) {
    public static MigrationConfig from(Map<String, Object> cfg, String projectName) {
        cfg = cfg == null ? Map.of() : cfg;
        return new MigrationConfig(
                str(cfg, "topicPrefix", sanitize(projectName)),
                tableIncludeList(cfg),
                str(cfg, "snapshotMode", "initial"),
                DeleteStrategy.valueOf(str(cfg, "deleteStrategy", "SOFT").toUpperCase()),
                str(cfg, "targetSchema", "public"),
                strList(cfg, "uuidColumns"),
                strList(cfg, "jsonColumns"),
                intVal(cfg, "tasksMax", 1),
                str(cfg, "schemaEvolution", "basic"),
                // Throughput defaults tuned for scale (#215): parallel snapshot workers + larger fetch.
                Math.max(1, intVal(cfg, "snapshotMaxThreads", 4)),
                Math.max(1, intVal(cfg, "snapshotFetchSize", 10000)),
                NamingStrategy.parse(str(cfg, "namingStrategy", "preserve")),
                "none".equalsIgnoreCase(str(cfg, "errorTolerance", "all")) ? "none" : "all",
                Math.max(1, intVal(cfg, "sinkBatchSize", 2000))
        );
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    /**
     * Debezium source {@code table.include.list}. Prefer an explicit {@code tableIncludeList}; otherwise
     * derive it from the project's {@code selectedTables} so the source captures exactly the chosen tables
     * instead of the whole schema. Falls back to {@code dbo.*} only when neither is set.
     */
    private static String tableIncludeList(Map<String, Object> cfg) {
        String explicit = str(cfg, "tableIncludeList", null);
        if (explicit != null) return explicit;
        List<String> selected = strList(cfg, "selectedTables");
        return selected.isEmpty() ? "dbo.*" : String.join(",", selected);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? def : Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Connector names must be DNS-ish; keep it conservative. */
    public static String sanitize(String s) {
        String out = s == null ? "project" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isBlank() ? "project" : out;
    }
}

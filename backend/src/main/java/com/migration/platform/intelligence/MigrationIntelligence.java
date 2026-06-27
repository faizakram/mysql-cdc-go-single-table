package com.migration.platform.intelligence;

import java.util.List;
import java.util.Map;

/**
 * Pure migration-intelligence helpers (#108): cost estimation math and error→remediation rules.
 * Deterministic and unit-testable; the recommendation layer (in the service) can later be backed by
 * an LLM without changing these primitives.
 */
public final class MigrationIntelligence {

    private MigrationIntelligence() {}

    // Conservative public-cloud-ish defaults; override per environment.
    public static final double USD_PER_COMPUTE_HOUR = 0.40;     // a mid-size worker
    public static final double USD_PER_GB_MONTH = 0.115;        // target storage

    public record CostEstimate(long rows, long bytes, long durationSeconds,
                               double computeUsd, double storageUsdPerMonth, double totalFirstMonthUsd,
                               List<String> assumptions) {}

    public static CostEstimate cost(long rows, long bytes, long durationSeconds) {
        double hours = durationSeconds / 3600.0;
        double compute = hours * USD_PER_COMPUTE_HOUR;
        double gb = bytes / (1024.0 * 1024 * 1024);
        double storage = gb * USD_PER_GB_MONTH;
        double total = compute + storage;
        return new CostEstimate(rows, bytes, durationSeconds,
                round(compute), round(storage), round(total),
                List.of("$" + USD_PER_COMPUTE_HOUR + "/compute-hr", "$" + USD_PER_GB_MONTH + "/GB-month",
                        "duration from plan throughput estimate"));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Map a connector/migration error message to an actionable remediation hint (#111). */
    public static String remediation(String error) {
        if (error == null) return "Check the connector task trace for the root cause.";
        String e = error.toLowerCase();
        for (var entry : RULES.entrySet()) if (e.contains(entry.getKey())) return entry.getValue();
        return "Inspect the connector task trace; verify connectivity, permissions and the table/type config.";
    }

    private static final Map<String, String> RULES = new java.util.LinkedHashMap<>() {{
        put("primary key", "Target upsert needs a primary key — pick a single-PK table or define a key, or use insert.mode=insert.");
        put("authentication failed", "Credentials rejected — verify the connection's username/password and that the account is enabled.");
        put("permission denied", "Grant the least-privilege role the required SELECT/DDL/DML (see SECURITY-database-accounts.md).");
        put("no maximum lsn", "SQL Server CDC capture not ready — enable CDC + start SQL Server Agent.");
        put("wal_level", "Set PostgreSQL wal_level=logical and restart for logical decoding.");
        put("binlog", "Enable MySQL binary logging with binlog_format=ROW.");
        put("unknown type", "An unmapped data type — set a per-project type override or adjust the mapping.");
        put("connection refused", "Host/port unreachable — check network, firewall and that the DB is listening.");
        put("deadlock", "Reduce parallelism (tasks.max / snapshot.max.threads) or retry; serialize conflicting tables.");
    }};
}

package com.migration.platform.advisor;

import java.util.ArrayList;
import java.util.List;

/**
 * Performance advisor (#217): turns observed throughput + lag and the current connector tuning into
 * concrete, actionable recommendations (raise snapshot threads, bigger sink batches, larger fetch size).
 * Pure decision logic — no I/O — so the heuristics are unit-tested; {@code PerformanceAdvisorService}
 * gathers the live signals and calls {@link #advise}.
 */
public final class PerformanceAdvisor {

    private PerformanceAdvisor() {}

    /** Lag thresholds beyond which the target is meaningfully behind and bigger batches are worth it. */
    static final long LAG_MS_WARN = 30_000;        // 30s replication lag on the hottest table
    static final long SINK_LAG_WARN = 50_000;      // 50k records behind on the sink consumer group

    /** Live signals for one project's current run. {@code sinkLagRecords} is null when unknown. */
    public record Signals(
            String jobStatus,            // SNAPSHOT | RUNNING | PAUSED | ... | null
            int tableCount,
            int snapshotMaxThreads,
            int snapshotFetchSize,
            int sinkBatchSize,
            double eventsPerSec,         // aggregate observed throughput
            long maxLagMs,               // worst per-table replication lag (ms)
            Long sinkLagRecords          // sink consumer-group lag (records), or null
    ) {}

    /** One recommendation. {@code severity} is OK | SUGGESTION | WARNING; setting/values null for OK. */
    public record Recommendation(String severity, String setting, String current, String suggested, String message) {}

    public static List<Recommendation> advise(Signals s) {
        List<Recommendation> out = new ArrayList<>();
        boolean snapshotting = "SNAPSHOT".equalsIgnoreCase(s.jobStatus());

        // 1. Snapshot parallelism under-utilised: many tables, few threads.
        if (snapshotting && s.snapshotMaxThreads() < 4 && s.tableCount() > s.snapshotMaxThreads()) {
            int suggested = Math.min(8, Math.max(4, Math.min(s.tableCount(), 8)));
            out.add(new Recommendation("SUGGESTION", "snapshot.max.threads",
                    String.valueOf(s.snapshotMaxThreads()), String.valueOf(suggested),
                    "Snapshotting " + s.tableCount() + " tables with only " + s.snapshotMaxThreads()
                            + " thread(s) — raise snapshot.max.threads to " + suggested + " to parallelise the initial load."));
        }

        // 2. Sink is behind (records or time): bigger batches apply changes to the target faster.
        boolean highSinkLag = s.sinkLagRecords() != null && s.sinkLagRecords() > SINK_LAG_WARN;
        boolean highTimeLag = s.maxLagMs() > LAG_MS_WARN;
        if ((highSinkLag || highTimeLag) && s.sinkBatchSize() < 5000) {
            int suggested = Math.min(10_000, Math.max(5_000, s.sinkBatchSize() * 2));
            out.add(new Recommendation("WARNING", "sink batch.size",
                    String.valueOf(s.sinkBatchSize()), String.valueOf(suggested),
                    lagDescription(s) + " Raise the sink batch.size to " + suggested
                            + " so the target applies changes faster and the lag drains."));
        }

        // 3. Small snapshot fetch size means more network round-trips per table during the snapshot.
        if (snapshotting && s.snapshotFetchSize() < 10_000) {
            out.add(new Recommendation("SUGGESTION", "snapshot.fetch.size",
                    String.valueOf(s.snapshotFetchSize()), "10000",
                    "snapshot.fetch.size is " + s.snapshotFetchSize()
                            + " — raising it to 10000 cuts round-trips on wide/large tables."));
        }

        // 4. Nothing actionable → reassure with the current numbers.
        if (out.isEmpty()) {
            out.add(new Recommendation("OK", null, null, null,
                    "Pipeline looks healthy — throughput " + Math.round(s.eventsPerSec())
                            + " ev/s, lag within thresholds. No tuning recommended."));
        }
        return out;
    }

    private static String lagDescription(Signals s) {
        if (s.sinkLagRecords() != null && s.sinkLagRecords() > SINK_LAG_WARN) {
            return "The sink is " + s.sinkLagRecords() + " records behind.";
        }
        return "Replication lag on the hottest table is ~" + (s.maxLagMs() / 1000) + "s.";
    }
}

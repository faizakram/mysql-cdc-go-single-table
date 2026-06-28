package com.migration.platform.monitoring;

import java.util.List;

/** Read models for the live sync monitor (#168). */
public final class LiveStreamDtos {

    private LiveStreamDtos() {}

    /**
     * Rolling throughput + lag for one source table.
     *
     * @param table         source table name
     * @param inserts       cumulative inserts (op=c) seen since the monitor started
     * @param updates       cumulative updates (op=u)
     * @param deletes       cumulative deletes (op=d)
     * @param reads         cumulative snapshot reads (op=r)
     * @param total         cumulative events
     * @param eventsPerSec  events/sec averaged over the rolling window
     * @param lastLagMs     replication lag of the most recent event (now − source ts_ms), ms
     * @param lastEventAgoMs age of the most recent event (now − last event), ms; -1 if none yet
     */
    public record TableThroughput(
            String projectId, String project, String table,
            long inserts, long updates, long deletes, long reads, long total,
            double eventsPerSec, long lastLagMs, long lastEventAgoMs
    ) {}

    /**
     * Whole-stream snapshot at an instant.
     *
     * @param epochMs        snapshot time
     * @param totalEventsPerSec aggregate events/sec across all tables
     * @param tables         per-table throughput, busiest first
     */
    public record LiveSnapshot(long epochMs, double totalEventsPerSec, List<TableThroughput> tables) {}
}

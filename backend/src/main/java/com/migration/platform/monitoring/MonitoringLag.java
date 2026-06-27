package com.migration.platform.monitoring;

import java.util.Map;

/** Pure lag arithmetic (unit-tested); the AdminClient I/O lives in {@link LagService}. */
public final class MonitoringLag {

    /** Total consumer lag = sum over partitions of max(0, endOffset - committedOffset). */
    public static <K> long totalLag(Map<K, Long> committed, Map<K, Long> end) {
        long lag = 0;
        for (var e : committed.entrySet()) {
            Long endOffset = end.get(e.getKey());
            if (endOffset == null) continue;
            lag += Math.max(0, endOffset - e.getValue());
        }
        return lag;
    }

    private MonitoringLag() {}
}

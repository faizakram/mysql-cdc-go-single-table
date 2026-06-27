package com.migration.platform.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic tests for the snapshot/CDC progress signals (#129). */
class ProgressTrackerTest {

    @Test
    void lookupFallsBackToCaseInsensitiveMatch() {
        Map<String, Long> byTable = Map.of("Employees", 42L, "orders", 7L);
        assertThat(ProgressTracker.lookup(byTable, "Employees")).isEqualTo(42L); // exact wins
        assertThat(ProgressTracker.lookup(byTable, "employees")).isEqualTo(42L); // case-insensitive
        assertThat(ProgressTracker.lookup(byTable, "ORDERS")).isEqualTo(7L);
        assertThat(ProgressTracker.lookup(byTable, "missing")).isNull();
    }

    @Test
    void failedTaskTraceReturnsTraceOfFirstFailedTask() {
        Map<String, Object> status = Map.of("tasks", List.of(
                Map.of("id", 0, "state", "RUNNING"),
                Map.of("id", 1, "state", "FAILED", "trace", "boom: connection refused")));
        assertThat(ProgressTracker.failedTaskTrace(status)).isEqualTo("boom: connection refused");
    }

    @Test
    void failedTaskTraceIsNullWhenAllRunning() {
        Map<String, Object> status = Map.of("tasks", List.of(Map.of("id", 0, "state", "RUNNING")));
        assertThat(ProgressTracker.failedTaskTrace(status)).isNull();
        assertThat(ProgressTracker.failedTaskTrace(null)).isNull();
    }

    @Test
    void snapshotCompleteFalseWhileSnapshotFlagPresent() {
        Map<String, Object> body = Map.of("offsets", List.of(
                Map.of("partition", Map.of("server", "inv"),
                        "offset", Map.of("snapshot", true, "snapshot_completed", false))));
        assertThat(ProgressTracker.parseSnapshotComplete(body)).isFalse();
    }

    @Test
    void snapshotCompleteTrueOnceFlagGone() {
        Map<String, Object> body = Map.of("offsets", List.of(
                Map.of("partition", Map.of("server", "inv"),
                        "offset", Map.of("file", "binlog.000003", "pos", 1234))));
        assertThat(ProgressTracker.parseSnapshotComplete(body)).isTrue();
    }

    @Test
    void snapshotCompleteNullWhenOffsetsUnavailable() {
        assertThat(ProgressTracker.parseSnapshotComplete(Map.of())).isNull();           // no endpoint
        assertThat(ProgressTracker.parseSnapshotComplete(Map.of("offsets", List.of()))).isNull(); // not committed yet
        assertThat(ProgressTracker.parseSnapshotComplete(null)).isNull();
    }

    @Test
    void isTruthyHandlesDebeziumSnapshotEncodings() {
        assertThat(ProgressTracker.isTruthy(true)).isTrue();
        assertThat(ProgressTracker.isTruthy("true")).isTrue();
        assertThat(ProgressTracker.isTruthy("incremental")).isTrue();
        assertThat(ProgressTracker.isTruthy(false)).isFalse();
        assertThat(ProgressTracker.isTruthy("last")).isFalse();
        assertThat(ProgressTracker.isTruthy(null)).isFalse();
    }
}

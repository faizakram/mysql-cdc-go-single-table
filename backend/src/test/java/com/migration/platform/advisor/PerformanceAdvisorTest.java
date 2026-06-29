package com.migration.platform.advisor;

import com.migration.platform.advisor.PerformanceAdvisor.Recommendation;
import com.migration.platform.advisor.PerformanceAdvisor.Signals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceAdvisorTest {

    private boolean has(List<Recommendation> recs, String setting) {
        return recs.stream().anyMatch(r -> setting.equals(r.setting()));
    }

    @Test
    void recommendsMoreSnapshotThreadsWhenUnderParallelised() {
        // Snapshotting 150 tables with 1 thread → suggest raising snapshot.max.threads.
        var recs = PerformanceAdvisor.advise(new Signals("SNAPSHOT", 150, 1, 10000, 2000, 0, 0, null));
        assertThat(has(recs, "snapshot.max.threads")).isTrue();
        var rec = recs.stream().filter(r -> "snapshot.max.threads".equals(r.setting())).findFirst().orElseThrow();
        assertThat(rec.suggested()).isEqualTo("8");
        assertThat(rec.severity()).isEqualTo("SUGGESTION");
    }

    @Test
    void recommendsBiggerSinkBatchWhenSinkIsBehind() {
        // RUNNING, sink 200k records behind, small batch → WARNING to raise batch.size.
        var recs = PerformanceAdvisor.advise(new Signals("RUNNING", 10, 4, 10000, 2000, 120, 0, 200_000L));
        assertThat(has(recs, "sink batch.size")).isTrue();
        var rec = recs.stream().filter(r -> "sink batch.size".equals(r.setting())).findFirst().orElseThrow();
        assertThat(rec.severity()).isEqualTo("WARNING");
        assertThat(Integer.parseInt(rec.suggested())).isGreaterThanOrEqualTo(5000);
    }

    @Test
    void recommendsBiggerFetchSizeDuringSnapshot() {
        var recs = PerformanceAdvisor.advise(new Signals("SNAPSHOT", 5, 4, 2000, 2000, 0, 0, null));
        assertThat(has(recs, "snapshot.fetch.size")).isTrue();
    }

    @Test
    void healthyPipelineGetsAnOkWithNoActionableSettings() {
        // RUNNING, well-tuned, low lag → single OK recommendation, no settings to change.
        var recs = PerformanceAdvisor.advise(new Signals("RUNNING", 10, 4, 10000, 5000, 240, 1500, 200L));
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).severity()).isEqualTo("OK");
        assertThat(recs.get(0).setting()).isNull();
    }

    @Test
    void wellTunedSnapshotDoesNotNagAboutThreadsOrFetch() {
        // 4 threads + 10000 fetch during snapshot → no thread/fetch suggestions.
        var recs = PerformanceAdvisor.advise(new Signals("SNAPSHOT", 50, 4, 10000, 2000, 500, 100, null));
        assertThat(has(recs, "snapshot.max.threads")).isFalse();
        assertThat(has(recs, "snapshot.fetch.size")).isFalse();
    }
}

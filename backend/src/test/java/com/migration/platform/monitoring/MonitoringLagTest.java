package com.migration.platform.monitoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringLagTest {

    @Test
    void sumsPerPartitionLagAndClampsNegatives() {
        long lag = MonitoringLag.totalLag(
                Map.of(0, 100L, 1, 50L, 2, 200L),   // committed
                Map.of(0, 105L, 1, 50L, 2, 199L));  // end (p2 end < committed -> clamp to 0)
        assertThat(lag).isEqualTo(5); // 5 + 0 + 0
    }

    @Test
    void zeroWhenCaughtUp() {
        assertThat(MonitoringLag.totalLag(Map.of(0, 10L), Map.of(0, 10L))).isZero();
    }

    @Test
    void ignoresPartitionsWithoutEndOffset() {
        assertThat(MonitoringLag.totalLag(Map.of(0, 10L, 1, 5L), Map.of(0, 12L))).isEqualTo(2);
    }
}

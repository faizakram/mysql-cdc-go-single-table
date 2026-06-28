package com.migration.platform.bulkload;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for the per-table fault-isolation summary (#217): when a bulk full load finishes
 * with some tables failed, the job-level error must report how many succeeded, how many failed, and which
 * (capped so a 300-table run doesn't produce a multi-kilobyte message).
 */
class BulkCopyFailureSummaryTest {

    @Test
    void summarizesAFewFailuresInline() {
        String s = BulkCopyService.failureSummary(8, List.of(
                "dbo.Orders: type mismatch",
                "dbo.Items: deadlock"));
        assertThat(s).isEqualTo("8/10 tables ok; 2 failed: dbo.Orders: type mismatch; dbo.Items: deadlock");
    }

    @Test
    void capsTheListAndCountsTheRemainder() {
        List<String> failures = List.of("t1: e", "t2: e", "t3: e", "t4: e", "t5: e", "t6: e", "t7: e");
        String s = BulkCopyService.failureSummary(3, failures);
        // 3 ok + 7 failed = 10 total; only the first 5 failures are listed, the rest summarized.
        assertThat(s).startsWith("3/10 tables ok; 7 failed: ");
        assertThat(s).contains("t1: e").contains("t5: e");
        assertThat(s).doesNotContain("t6: e").doesNotContain("t7: e");
        assertThat(s).endsWith("…(+2 more)");
    }

    @Test
    void singleFailureHasNoSeparatorOrRemainder() {
        String s = BulkCopyService.failureSummary(0, List.of("dbo.Only: boom"));
        assertThat(s).isEqualTo("0/1 tables ok; 1 failed: dbo.Only: boom");
    }
}

package com.migration.platform.planning;

import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import com.migration.platform.planning.dto.PlanDtos.PlanTable;
import com.migration.platform.planning.dto.PlanDtos.TableInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationPlanLogicTest {

    private TableInput t(String fq, long rows, boolean pk, int unmappable) {
        return new TableInput(fq, rows, 0, pk, unmappable);
    }
    private int level(MigrationPlan p, String fq) {
        return p.tables().stream().filter(x -> x.fqName().equals(fq)).findFirst().map(PlanTable::level).orElse(-99);
    }

    @Test
    void parentsAreOrderedBeforeChildren() {
        // order: order_item -> order -> customer (item refs order refs customer)
        var plan = MigrationPlanLogic.plan(
                List.of(t("dbo.customer", 10, true, 0), t("dbo.order", 100, true, 0), t("dbo.order_item", 500, true, 0)),
                Map.of("dbo.order", Set.of("dbo.customer"), "dbo.order_item", Set.of("dbo.order")),
                5000);
        assertThat(plan.hasCycles()).isFalse();
        assertThat(level(plan, "dbo.customer")).isEqualTo(0);
        assertThat(level(plan, "dbo.order")).isEqualTo(1);
        assertThat(level(plan, "dbo.order_item")).isEqualTo(2);
        assertThat(plan.levels()).isEqualTo(3);
    }

    @Test
    void independentTablesShareLevelZeroForParallelism() {
        var plan = MigrationPlanLogic.plan(
                List.of(t("dbo.a", 1, true, 0), t("dbo.b", 1, true, 0)), Map.of(), 5000);
        assertThat(level(plan, "dbo.a")).isEqualTo(0);
        assertThat(level(plan, "dbo.b")).isEqualTo(0);
        assertThat(plan.levels()).isEqualTo(1);
    }

    @Test
    void circularDependenciesAreDetectedAndFlagged() {
        var plan = MigrationPlanLogic.plan(
                List.of(t("dbo.a", 1, true, 0), t("dbo.b", 1, true, 0)),
                Map.of("dbo.a", Set.of("dbo.b"), "dbo.b", Set.of("dbo.a")),
                5000);
        assertThat(plan.hasCycles()).isTrue();
        assertThat(plan.tables()).allMatch(x -> x.risks().contains("CIRCULAR_FK"));
    }

    @Test
    void risksAndEstimatesAreComputed() {
        var plan = MigrationPlanLogic.plan(
                List.of(t("dbo.huge", 60_000_000L, false, 2)), Map.of(), 5000);
        PlanTable huge = plan.tables().get(0);
        assertThat(huge.risks()).contains("NO_PRIMARY_KEY", "HUGE_TABLE");
        assertThat(huge.risks().stream().anyMatch(r -> r.startsWith("UNSUPPORTED_TYPES"))).isTrue();
        assertThat(plan.totalRows()).isEqualTo(60_000_000L);
        assertThat(plan.estimatedSeconds()).isEqualTo(60_000_000L / 5000);
    }
}

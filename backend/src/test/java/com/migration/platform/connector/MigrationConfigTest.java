package com.migration.platform.connector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationConfigTest {

    @Test
    void defaultsWhenConfigEmpty() {
        MigrationConfig mc = MigrationConfig.from(Map.of(), "My Project");
        assertThat(mc.topicPrefix()).isEqualTo("my-project");
        assertThat(mc.tableIncludeList()).isEqualTo("dbo.*");
        assertThat(mc.snapshotMode()).isEqualTo("initial");
        assertThat(mc.deleteStrategy()).isEqualTo(DeleteStrategy.SOFT);
        assertThat(mc.targetSchema()).isEqualTo("public");
        assertThat(mc.tasksMax()).isEqualTo(1);
        assertThat(mc.uuidColumns()).isEmpty();
    }

    @Test
    void parsesProvidedValuesAndBothListForms() {
        MigrationConfig mc = MigrationConfig.from(Map.of(
                "deleteStrategy", "hard",
                "uuidColumns", "a, b ,c",
                "jsonColumns", List.of("x", "y"),
                "tasksMax", 4
        ), "p");
        assertThat(mc.deleteStrategy()).isEqualTo(DeleteStrategy.HARD);
        assertThat(mc.uuidColumns()).containsExactly("a", "b", "c");
        assertThat(mc.jsonColumns()).containsExactly("x", "y");
        assertThat(mc.tasksMax()).isEqualTo(4);
    }

    @Test
    void sanitizeProducesConnectorSafeNames() {
        assertThat(MigrationConfig.sanitize("Employees → PG (prod)!")).isEqualTo("employees-pg-prod");
        assertThat(MigrationConfig.sanitize("   ")).isEqualTo("project");
        assertThat(MigrationConfig.sanitize(null)).isEqualTo("project");
    }
}

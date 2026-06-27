package com.migration.platform.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TargetNaming mirrors the runtime case-transform SMT so validation/reconciliation locate the
 * migrated identifiers. The soft-delete marker is the tricky case: its leading underscores must be
 * dropped under snake_case (-> cdc_deleted), matching what the SMT writes to the target.
 */
class TargetNamingTest {

    @Test
    void mapsOrdinaryIdentifiers() {
        assertThat(TargetNaming.apply("OrderItems", NamingStrategy.SNAKE_CASE)).isEqualTo("order_items");
        assertThat(TargetNaming.apply("OrderItemId", NamingStrategy.SNAKE_CASE)).isEqualTo("order_item_id");
        assertThat(TargetNaming.apply("DataTypeShowcase", NamingStrategy.SNAKE_CASE)).isEqualTo("data_type_showcase");
        assertThat(TargetNaming.apply("OrderItems", NamingStrategy.PRESERVE)).isEqualTo("OrderItems");
    }

    @Test
    void softDeleteMarkerFollowsTheNamingStrategy() {
        // The SMT renames __cdc_deleted -> cdc_deleted under snake_case; validation must match.
        assertThat(TargetNaming.apply("__cdc_deleted", NamingStrategy.SNAKE_CASE)).isEqualTo("cdc_deleted");
        assertThat(TargetNaming.apply("__cdc_deleted", NamingStrategy.PRESERVE)).isEqualTo("__cdc_deleted");
        assertThat(TargetNaming.apply("__cdc_deleted", NamingStrategy.UPPER_CASE)).isEqualTo("CDC_DELETED");
        assertThat(TargetNaming.apply("__cdc_deleted", NamingStrategy.CAMEL_CASE)).isEqualTo("cdcDeleted");
        assertThat(TargetNaming.apply("__cdc_deleted", NamingStrategy.PASCAL_CASE)).isEqualTo("CdcDeleted");
    }
}

package com.migration.platform.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationLogicTest {

    @Test
    void snakeCaseMatchesTheSmtAlgorithm() {
        assertThat(ReconciliationLogic.snakeCase("EmployeeID")).isEqualTo("employee_id");
        assertThat(ReconciliationLogic.snakeCase("FirstName")).isEqualTo("first_name");
        assertThat(ReconciliationLogic.snakeCase("HTTPSConnection")).isEqualTo("https_connection");
        assertThat(ReconciliationLogic.snakeCase("already_snake")).isEqualTo("already_snake");
        assertThat(ReconciliationLogic.snakeCase("Orders")).isEqualTo("orders");
    }

    @Test
    void countOutcomeFlagsMismatch() {
        assertThat(ReconciliationLogic.countOutcome(100, 100).status()).isEqualTo("MATCH");
        var diff = ReconciliationLogic.countOutcome(100, 97);
        assertThat(diff.status()).isEqualTo("MISMATCH");
        assertThat(diff.difference()).isEqualTo(3);
    }

    @Test
    void evaluateSampleCountsMissingKeys() {
        var all = ReconciliationLogic.evaluateSample(List.of("1", "2", "3"), List.of("1", "2", "3"));
        assertThat(all.status()).isEqualTo("MATCH");
        assertThat(all.missing()).isZero();

        var some = ReconciliationLogic.evaluateSample(List.of("1", "2", "3", "4"), List.of("1", "3"));
        assertThat(some.sampled()).isEqualTo(4);
        assertThat(some.missing()).isEqualTo(2);
        assertThat(some.status()).isEqualTo("MISMATCH");
    }

    @Test
    void normalizeValueCanonicalisesEquivalentForms() {
        assertThat(ReconciliationLogic.normalizeValue(null)).isEqualTo("∅");
        assertThat(ReconciliationLogic.normalizeValue(new java.math.BigDecimal("1.50")))
                .isEqualTo(ReconciliationLogic.normalizeValue(new java.math.BigDecimal("1.5")));
        assertThat(ReconciliationLogic.normalizeValue(42)).isEqualTo("42");
        assertThat(ReconciliationLogic.normalizeValue(Boolean.TRUE)).isEqualTo("true");
        assertThat(ReconciliationLogic.normalizeValue("  abc  ")).isEqualTo("abc");
    }

    @Test
    void rowChecksumIsStableAndOrderSensitive() {
        var a = ReconciliationLogic.rowChecksum(List.of("1", "alice", "true"));
        var b = ReconciliationLogic.rowChecksum(List.of("1", "alice", "true"));
        var changed = ReconciliationLogic.rowChecksum(List.of("1", "bob", "true"));
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(changed);
        assertThat(a).hasSize(64); // SHA-256 hex
    }

    @Test
    void normalizeKeyLowercasesAndTrims() {
        assertThat(ReconciliationLogic.normalizeKey("  ABC-123  ")).isEqualTo("abc-123");
        assertThat(ReconciliationLogic.normalizeKey(42)).isEqualTo("42");
        assertThat(ReconciliationLogic.normalizeKey(null)).isNull();
    }

    @Test
    void compositeKeyJoinsNormalisedPartsAndIsOrderSensitive() {
        // #217: composite-PK content validation builds one key from all PK columns in key order.
        String sep = ReconciliationLogic.KEY_SEP;
        assertThat(ReconciliationLogic.compositeKey(List.of("A", 7)))
                .isEqualTo("a" + sep + "7");
        // A single-column key matches the plain normalized form (back-compat with single-PK checksum).
        assertThat(ReconciliationLogic.compositeKey(List.of("Order-9")))
                .isEqualTo(ReconciliationLogic.normalizeKey("Order-9"));
        // Order matters: (a,b) and (b,a) are distinct keys.
        assertThat(ReconciliationLogic.compositeKey(List.of("a", "b")))
                .isNotEqualTo(ReconciliationLogic.compositeKey(List.of("b", "a")));
    }

    @Test
    void compositeKeyIsNullWhenAnyPartIsNull() {
        // A null PK component can't be reliably matched across engines — skip the row.
        java.util.List<Object> withNull = new java.util.ArrayList<>();
        withNull.add("a");
        withNull.add(null);
        assertThat(ReconciliationLogic.compositeKey(withNull)).isNull();
    }
}

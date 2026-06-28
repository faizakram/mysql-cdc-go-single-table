package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationLogicTest {

    @Test
    void passesWhenEverythingMatches() {
        TableValidation v = ValidationLogic.assess("dbo", "employee", 100, 100, 0, 0, 0, 0, 80, 15, 5);
        assertThat(v.status()).isEqualTo("PASS");
        assertThat(v.issues()).isEmpty();
    }

    @Test
    void carriesCdcOpCountsThroughWithoutAffectingStatus() {
        TableValidation v = ValidationLogic.assess("dbo", "employee", 100, 100, 0, 0, 0, 0, 80, 15, 5);
        assertThat(v.cdcInserts()).isEqualTo(80);
        assertThat(v.cdcUpdates()).isEqualTo(15);
        assertThat(v.cdcDeletes()).isEqualTo(5);
        assertThat(v.status()).isEqualTo("PASS");   // CDC activity is informational only
    }

    @Test
    void flagsEachKindOfDefect() {
        TableValidation v = ValidationLogic.assess("dbo", "employee", 100, 98, 1, 3, 2, 0, -1, -1, -1);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("ROW_COUNT_MISMATCH"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("NULL_PRIMARY_KEY"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("DUPLICATE_KEYS"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("MISSING_ROWS"));
    }

    @Test
    void extraRowsAreFlagged() {
        TableValidation v = ValidationLogic.assess("dbo", "t", 100, 105, 0, 0, 0, 5, -1, -1, -1);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("EXTRA_ROWS"));
    }

    @Test
    void targetBehindOnCountOnlyIsSyncingNotFail() {
        // Live replication lag: target a few rows behind, every structural signal clean (#166).
        TableValidation v = ValidationLogic.assess("dbo", "orders", 1000, 998, 0, 0, 0, 0, 500, 100, 50);
        assertThat(v.status()).isEqualTo("SYNCING");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("TARGET_BEHIND"));
        assertThat(v.issues()).noneMatch(i -> i.startsWith("ROW_COUNT_MISMATCH"));
    }

    @Test
    void missingSampledRowsFailEvenWhenOnlyCountBehind() {
        // Same count gap, but sampled source rows are genuinely absent on the target → real FAIL.
        TableValidation v = ValidationLogic.assess("dbo", "orders", 1000, 998, 0, 0, 2, 0, -1, -1, -1);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("MISSING_ROWS"));
    }

    @Test
    void targetAheadIsAlwaysFail() {
        // Target has rows the source doesn't (lost delete / over-replication) → never SYNCING.
        TableValidation v = ValidationLogic.assess("dbo", "orders", 1000, 1010, 0, 0, 0, 10, -1, -1, -1);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("EXTRA_ROWS"));
    }
}

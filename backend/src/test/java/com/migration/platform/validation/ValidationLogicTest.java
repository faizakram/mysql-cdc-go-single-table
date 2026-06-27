package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationLogicTest {

    @Test
    void passesWhenEverythingMatches() {
        TableValidation v = ValidationLogic.assess("dbo", "employee", 100, 100, 0, 0, 0, 0);
        assertThat(v.status()).isEqualTo("PASS");
        assertThat(v.issues()).isEmpty();
    }

    @Test
    void flagsEachKindOfDefect() {
        TableValidation v = ValidationLogic.assess("dbo", "employee", 100, 98, 1, 3, 2, 0);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("ROW_COUNT_MISMATCH"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("NULL_PRIMARY_KEY"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("DUPLICATE_KEYS"));
        assertThat(v.issues()).anyMatch(i -> i.startsWith("MISSING_ROWS"));
    }

    @Test
    void extraRowsAreFlagged() {
        TableValidation v = ValidationLogic.assess("dbo", "t", 100, 105, 0, 0, 0, 5);
        assertThat(v.status()).isEqualTo("FAIL");
        assertThat(v.issues()).anyMatch(i -> i.startsWith("EXTRA_ROWS"));
    }
}

package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trip mapping between the computed {@link TableValidation} record and the persisted entity (#150). */
class ValidationResultMappingTest {

    @Test
    void roundTripsAllFieldsIncludingCdcCountsAndIssues() {
        UUID runId = UUID.randomUUID();
        TableValidation tv = new TableValidation("dbo", "orders",
                1000, 998, 1, 2, 2, 0, 800, 150, 50, "FAIL",
                List.of("ROW_COUNT_MISMATCH(1000 vs 998)", "MISSING_ROWS(2)"));

        TableValidation back = ValidationResult.from(runId, tv).toDto();

        assertThat(back.schema()).isEqualTo("dbo");
        assertThat(back.table()).isEqualTo("orders");
        assertThat(back.sourceRows()).isEqualTo(1000);
        assertThat(back.targetRows()).isEqualTo(998);
        assertThat(back.nullPrimaryKey()).isEqualTo(1);
        assertThat(back.duplicateKeys()).isEqualTo(2);
        assertThat(back.missingRows()).isEqualTo(2);
        assertThat(back.extraRows()).isEqualTo(0);
        assertThat(back.cdcInserts()).isEqualTo(800);
        assertThat(back.cdcUpdates()).isEqualTo(150);
        assertThat(back.cdcDeletes()).isEqualTo(50);
        assertThat(back.status()).isEqualTo("FAIL");
        assertThat(back.issues()).containsExactly("ROW_COUNT_MISMATCH(1000 vs 998)", "MISSING_ROWS(2)");
    }

    @Test
    void emptyIssuesRoundTripToEmptyList() {
        TableValidation tv = new TableValidation("dbo", "ok",
                10, 10, 0, 0, 0, 0, -1, -1, -1, "PASS", List.of());
        TableValidation back = ValidationResult.from(UUID.randomUUID(), tv).toDto();
        assertThat(back.issues()).isEmpty();
        assertThat(back.cdcInserts()).isEqualTo(-1);   // N/A preserved
        assertThat(back.status()).isEqualTo("PASS");
    }
}

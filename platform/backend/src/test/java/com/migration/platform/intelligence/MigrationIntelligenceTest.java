package com.migration.platform.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIntelligenceTest {

    @Test
    void costScalesWithDurationAndStorage() {
        var c = MigrationIntelligence.cost(1_000_000, 10L * 1024 * 1024 * 1024, 3600);
        assertThat(c.computeUsd()).isEqualTo(0.40);                 // 1 hour * $0.40
        assertThat(c.storageUsdPerMonth()).isGreaterThan(1.0);      // 10 GB * $0.115
        assertThat(c.totalFirstMonthUsd()).isEqualTo(Math.round((c.computeUsd() + c.storageUsdPerMonth()) * 100) / 100.0);
    }

    @Test
    void remediationMatchesKnownErrors() {
        assertThat(MigrationIntelligence.remediation("FATAL: authentication failed for user")).contains("Credentials");
        assertThat(MigrationIntelligence.remediation("relation has no primary key")).contains("primary key");
        assertThat(MigrationIntelligence.remediation("wal_level must be logical")).contains("wal_level");
        assertThat(MigrationIntelligence.remediation(null)).isNotBlank();
        assertThat(MigrationIntelligence.remediation("totally novel error")).contains("connector task trace");
    }
}

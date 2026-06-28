package com.migration.platform.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the integrity validation job (#152), kept separate from PlatformProperties to keep
 * bindings focused. Sensible defaults apply when {@code platform.validation.*} is unset.
 *
 * @param queryTimeoutSeconds per-query JDBC timeout so one giant table can't hang a whole run; a
 *                            timeout surfaces as that table's ERROR rather than failing the report.
 *                            0 disables the timeout.
 * @param missingSampleSize   number of source primary keys sampled for the missing-row check.
 * @param approximateCounts   when true, source/target row counts use fast engine catalog statistics
 *                            (e.g. PostgreSQL reltuples) instead of an exact COUNT(*); much cheaper
 *                            on multi-million-row tables, at the cost of being approximate.
 */
@ConfigurationProperties(prefix = "platform.validation")
public record ValidationProperties(int queryTimeoutSeconds, int missingSampleSize, boolean approximateCounts,
                                   long recheckSettleMs) {
    public ValidationProperties {
        if (queryTimeoutSeconds < 0) queryTimeoutSeconds = 0;
        if (missingSampleSize < 1) missingSampleSize = 1000;
        if (recheckSettleMs < 0) recheckSettleMs = 0;
    }
}

package com.migration.platform.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit-log config (#57).
 *
 * @param retentionDays how long to keep audit entries before the retention sweep deletes them.
 */
@ConfigurationProperties(prefix = "platform.audit")
public record AuditProperties(int retentionDays) {
    public AuditProperties {
        if (retentionDays < 1) retentionDays = 90;
    }
}

package com.migration.platform.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Job-orchestration config (#54). Separate from PlatformProperties to keep bindings focused.
 *
 * @param maxConcurrent maximum number of orchestrated tasks (full-load / validation) running at
 *                      once across all projects. Excess submissions queue (backpressure).
 */
@ConfigurationProperties(prefix = "platform.orchestrator")
public record OrchestratorProperties(int maxConcurrent) {
    public OrchestratorProperties {
        if (maxConcurrent < 1) maxConcurrent = 1;
    }
}

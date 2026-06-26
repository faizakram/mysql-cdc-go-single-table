package com.migration.platform.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alerting config (#52). Separate from PlatformProperties to keep bindings focused.
 * {@code webhookUrl} accepts any Slack/Teams/custom incoming-webhook URL (JSON {"text": ...}).
 */
@ConfigurationProperties(prefix = "platform.alerts")
public record AlertProperties(String webhookUrl, String monitorCron, long lagThreshold) {}

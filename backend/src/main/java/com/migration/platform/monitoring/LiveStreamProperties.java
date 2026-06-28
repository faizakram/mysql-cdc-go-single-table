package com.migration.platform.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live sync-monitor config (#168). The monitor tails the Debezium CDC topics as a Kafka consumer
 * to compute real-time per-table throughput and lag.
 *
 * @param enabled       master switch for the Kafka-consuming monitor.
 * @param windowSeconds rolling window over which per-table event rates are averaged.
 * @param topicPattern  regex of topics to tail. Default matches CDC table topics
 *                      ({@code prefix.db.schema.table} / {@code prefix.schema.table} — at least two
 *                      dots) while excluding internal topics (connect_*, schema-changes.*).
 */
@ConfigurationProperties(prefix = "platform.monitoring.live")
public record LiveStreamProperties(boolean enabled, int windowSeconds, String topicPattern) {
    public LiveStreamProperties {
        if (windowSeconds < 1) windowSeconds = 10;
        if (topicPattern == null || topicPattern.isBlank()) topicPattern = "^[^.]+(\\.[^.]+){2,}$";
    }
}

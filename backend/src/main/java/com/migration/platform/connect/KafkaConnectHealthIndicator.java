package com.migration.platform.connect;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces Kafka Connect (data-plane) reachability in {@code /actuator/health} as a "kafkaConnect"
 * component (#177). Previously health reflected only the metadata DB, so the app could look healthy
 * while all connector operations were failing. This indicator is intentionally NOT part of the
 * Kubernetes readiness group (see application.yml) — a Connect outage shouldn't pull the API pod out
 * of rotation, but it must be visible to operators and dashboards.
 */
@Component
public class KafkaConnectHealthIndicator implements HealthIndicator {

    private final KafkaConnectClient connect;

    public KafkaConnectHealthIndicator(KafkaConnectClient connect) {
        this.connect = connect;
    }

    @Override
    public Health health() {
        try {
            String version = connect.ping();
            return Health.up().withDetail("version", version).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}

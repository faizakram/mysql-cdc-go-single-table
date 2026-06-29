package com.migration.platform.connect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Kafka Connect reachability health indicator (#177): UP with the Connect version when reachable, DOWN
 * with the error when not — so /actuator/health reflects data-plane reachability for dashboards/operators.
 */
@ExtendWith(MockitoExtension.class)
class KafkaConnectHealthIndicatorTest {

    @Mock KafkaConnectClient connect;

    @Test
    void reportsUpWithVersionWhenConnectIsReachable() {
        when(connect.ping()).thenReturn("3.8.0");
        Health h = new KafkaConnectHealthIndicator(connect).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("version", "3.8.0");
    }

    @Test
    void reportsDownWithErrorWhenConnectIsUnreachable() {
        when(connect.ping()).thenThrow(new RuntimeException("connection refused"));
        Health h = new KafkaConnectHealthIndicator(connect).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("error", "connection refused");
    }
}

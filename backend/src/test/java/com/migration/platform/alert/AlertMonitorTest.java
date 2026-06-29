package com.migration.platform.alert;

import com.migration.platform.connect.KafkaConnectClient;
import com.migration.platform.monitoring.MonitoringService;
import com.migration.platform.monitoring.dto.ConnectorHealth;
import com.migration.platform.monitoring.dto.ProjectHealth;
import com.migration.platform.monitoring.dto.TaskHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-heal behavior of the alert sweep (#176): a FAILED connector/task is auto-restarted best-effort
 * before the CONNECTOR_FAILED alert is raised; a healthy connector resolves its alert and is left alone.
 */
@ExtendWith(MockitoExtension.class)
class AlertMonitorTest {

    @Mock MonitoringService monitoring;
    @Mock AlertService alerts;
    @Mock KafkaConnectClient connect;

    private AlertMonitor monitor() {
        // Real props with lagThreshold=0 so the lag branch is skipped (no lag in these cases).
        return new AlertMonitor(monitoring, alerts, new AlertProperties(null, "0 */2 * * * *", 0), connect);
    }

    private ProjectHealth project(ConnectorHealth c) {
        return new ProjectHealth(UUID.randomUUID(), "proj", UUID.randomUUID(), "RUNNING", false, null, List.of(c));
    }

    @Test
    void restartsAFailedConnectorAndRaisesAnAlert() {
        ConnectorHealth c = new ConnectorHealth("orders-source", "source", "FAILED", null, List.of());
        when(monitoring.overview()).thenReturn(List.of(project(c)));

        monitor().checkConnectorHealth();

        verify(connect).restart("orders-source");
        verify(alerts).raise(eq("connector:orders-source"), eq("CRITICAL"), eq("CONNECTOR_FAILED"), anyString(), any());
    }

    @Test
    void restartsOnlyTheFailedTaskWhenTheConnectorItselfIsRunning() {
        ConnectorHealth c = new ConnectorHealth("orders-sink", "sink", "RUNNING", null,
                List.of(new TaskHealth(0, "RUNNING", "w", null), new TaskHealth(1, "FAILED", "w", "boom")));
        when(monitoring.overview()).thenReturn(List.of(project(c)));

        monitor().checkConnectorHealth();

        verify(connect).restartTask("orders-sink", 1);
        verify(connect, never()).restart(anyString());
        verify(alerts).raise(eq("connector:orders-sink"), eq("CRITICAL"), eq("CONNECTOR_FAILED"), anyString(), any());
    }

    @Test
    void resolvesAndLeavesAHealthyConnectorAlone() {
        ConnectorHealth c = new ConnectorHealth("orders-sink", "sink", "RUNNING", null,
                List.of(new TaskHealth(0, "RUNNING", "w", null)));
        when(monitoring.overview()).thenReturn(List.of(project(c)));

        monitor().checkConnectorHealth();

        verify(alerts).resolve("connector:orders-sink");
        verify(connect, never()).restart(anyString());
        verify(connect, never()).restartTask(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
}

package com.migration.platform.alert;

import com.migration.platform.connect.KafkaConnectClient;
import com.migration.platform.monitoring.MonitoringService;
import com.migration.platform.monitoring.dto.ConnectorHealth;
import com.migration.platform.monitoring.dto.ProjectHealth;
import com.migration.platform.monitoring.dto.TaskHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically checks live connector health and raises/resolves CONNECTOR_FAILED alerts (#52).
 * Only acts on a genuine FAILED state — NOT_FOUND/UNKNOWN (e.g. data plane simply not running)
 * is ignored to avoid noise. A FAILED connector/task is also auto-restarted best-effort (#176) so a
 * transient failure self-heals; if it keeps failing it stays alerted.
 */
@Component
public class AlertMonitor {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

    private final MonitoringService monitoring;
    private final AlertService alerts;
    private final AlertProperties props;
    private final KafkaConnectClient connect;

    public AlertMonitor(MonitoringService monitoring, AlertService alerts, AlertProperties props,
                        KafkaConnectClient connect) {
        this.monitoring = monitoring;
        this.alerts = alerts;
        this.props = props;
        this.connect = connect;
    }

    @Scheduled(cron = "${platform.alerts.monitor-cron}")
    public void checkConnectorHealth() {
        try {
            for (ProjectHealth p : monitoring.overview()) {
                for (ConnectorHealth c : p.connectors()) {
                    String dedup = "connector:" + c.name();
                    if (isFailed(c)) {
                        autoRestart(c);   // best-effort self-heal before alerting (#176)
                        alerts.raise(dedup, "CRITICAL", "CONNECTOR_FAILED",
                                "Connector '" + c.name() + "' (" + c.role() + ") is " + c.state()
                                        + " in project '" + p.projectName() + "' — auto-restart attempted",
                                p.projectId());
                    } else if ("RUNNING".equals(c.state())) {
                        alerts.resolve(dedup);
                    }
                }
                // Lag-threshold alerting (#28).
                String lagDedup = "lag:" + p.projectId();
                if (props.lagThreshold() > 0 && p.lagRecords() != null && p.lagRecords() > props.lagThreshold()) {
                    alerts.raise(lagDedup, "WARNING", "LAG",
                            "Sink lag for project '" + p.projectName() + "' is " + p.lagRecords()
                                    + " records (threshold " + props.lagThreshold() + ")", p.projectId());
                } else if (p.lagRecords() != null) {
                    alerts.resolve(lagDedup);
                }
            }
        } catch (Exception e) {
            log.warn("Alert monitor sweep failed: {}", e.getMessage());
        }
    }

    private boolean isFailed(ConnectorHealth c) {
        return "FAILED".equals(c.state())
                || c.tasks().stream().anyMatch(t -> "FAILED".equals(t.state()));
    }

    /**
     * Best-effort recovery of a FAILED connector/task (#176): restart the whole connector if it's
     * FAILED, otherwise restart just the failed task(s). A genuinely-broken connector simply re-fails
     * next sweep and stays alerted; a transient failure self-heals. Never throws.
     */
    private void autoRestart(ConnectorHealth c) {
        try {
            if ("FAILED".equals(c.state())) {
                connect.restart(c.name());
                log.info("Auto-restarted FAILED connector '{}'", c.name());
            } else {
                for (TaskHealth t : c.tasks()) {
                    if ("FAILED".equals(t.state())) {
                        connect.restartTask(c.name(), t.id());
                        log.info("Auto-restarted FAILED task {}#{}", c.name(), t.id());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Auto-restart of connector '{}' failed: {}", c.name(), e.getMessage());
        }
    }
}

package com.migration.platform.alert;

import com.migration.platform.monitoring.MonitoringService;
import com.migration.platform.monitoring.dto.ConnectorHealth;
import com.migration.platform.monitoring.dto.ProjectHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically checks live connector health and raises/resolves CONNECTOR_FAILED alerts (#52).
 * Only acts on a genuine FAILED state — NOT_FOUND/UNKNOWN (e.g. data plane simply not running)
 * is ignored to avoid noise.
 */
@Component
public class AlertMonitor {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

    private final MonitoringService monitoring;
    private final AlertService alerts;
    private final AlertProperties props;

    public AlertMonitor(MonitoringService monitoring, AlertService alerts, AlertProperties props) {
        this.monitoring = monitoring;
        this.alerts = alerts;
        this.props = props;
    }

    @Scheduled(cron = "${platform.alerts.monitor-cron}")
    public void checkConnectorHealth() {
        try {
            for (ProjectHealth p : monitoring.overview()) {
                for (ConnectorHealth c : p.connectors()) {
                    String dedup = "connector:" + c.name();
                    if (isFailed(c)) {
                        alerts.raise(dedup, "CRITICAL", "CONNECTOR_FAILED",
                                "Connector '" + c.name() + "' (" + c.role() + ") is " + c.state()
                                        + " in project '" + p.projectName() + "'", p.projectId());
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
}

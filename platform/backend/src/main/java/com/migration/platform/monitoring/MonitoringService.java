package com.migration.platform.monitoring;

import com.migration.platform.common.NotFoundException;
import com.migration.platform.connect.KafkaConnectClient;
import com.migration.platform.job.JobRepository;
import com.migration.platform.job.MigrationJob;
import com.migration.platform.monitoring.dto.ConnectorHealth;
import com.migration.platform.monitoring.dto.ProjectHealth;
import com.migration.platform.monitoring.dto.TaskHealth;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates live connector/task health from Kafka Connect for the real-time dashboard (issue #39),
 * and seeds the monitoring epic (#50). Read-only; tolerant of connectors that are absent/unreachable.
 */
@Service
public class MonitoringService {

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final KafkaConnectClient connect;
    private final LagService lagService;

    public MonitoringService(ProjectRepository projects, JobRepository jobs, KafkaConnectClient connect,
                             LagService lagService) {
        this.projects = projects;
        this.jobs = jobs;
        this.connect = connect;
        this.lagService = lagService;
    }

    /** Health for every project that has at least one run with deployed connectors. */
    @Transactional(readOnly = true)
    public List<ProjectHealth> overview() {
        List<ProjectHealth> out = new ArrayList<>();
        for (MigrationProject p : projects.findAll()) {
            latestJobWithConnectors(p.getId()).ifPresent(job -> out.add(buildHealth(p, job)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProjectHealth projectStatus(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        return latestJobWithConnectors(projectId)
                .map(job -> buildHealth(p, job))
                .orElse(new ProjectHealth(p.getId(), p.getName(), null, "NO_RUN", false, null, List.of()));
    }

    private java.util.Optional<MigrationJob> latestJobWithConnectors(UUID projectId) {
        return jobs.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(j -> j.getSourceConnectorName() != null || j.getSinkConnectorName() != null)
                .findFirst();
    }

    private ProjectHealth buildHealth(MigrationProject p, MigrationJob job) {
        List<ConnectorHealth> connectors = new ArrayList<>();
        if (job.getSourceConnectorName() != null) {
            connectors.add(connectorHealth(job.getSourceConnectorName(), "source"));
        }
        if (job.getSinkConnectorName() != null) {
            connectors.add(connectorHealth(job.getSinkConnectorName(), "sink"));
        }
        boolean healthy = !connectors.isEmpty() && connectors.stream().allMatch(ConnectorHealth::healthy);
        Long lag = job.getSinkConnectorName() != null
                ? lagService.consumerGroupLag("connect-" + job.getSinkConnectorName()) : null;
        return new ProjectHealth(p.getId(), p.getName(), job.getId(), job.getStatus().name(), healthy, lag, connectors);
    }

    @SuppressWarnings("unchecked")
    private ConnectorHealth connectorHealth(String name, String role) {
        try {
            Map<String, Object> st = connect.connectorStatus(name);
            Map<String, Object> connector = (Map<String, Object>) st.get("connector");
            String state = connector != null ? str(connector.get("state")) : "UNKNOWN";
            String worker = connector != null ? str(connector.get("worker_id")) : null;

            List<TaskHealth> tasks = new ArrayList<>();
            Object rawTasks = st.get("tasks");
            if (rawTasks instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> t = (Map<String, Object>) o;
                    tasks.add(new TaskHealth(
                            t.get("id") instanceof Number n ? n.intValue() : 0,
                            str(t.get("state")),
                            str(t.get("worker_id")),
                            str(t.get("trace"))));
                }
            }
            return new ConnectorHealth(name, role, state, worker, tasks);
        } catch (Exception e) {
            // Connector not yet created, deleted, or Connect unreachable.
            return new ConnectorHealth(name, role, "NOT_FOUND", null, List.of());
        }
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}

package com.migration.platform.monitoring;

import com.migration.platform.monitoring.dto.ProjectHealth;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final MonitoringService monitoring;

    public MonitoringController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    /** Live health for all projects with deployed connectors (dashboard overview). */
    @GetMapping("/overview")
    public List<ProjectHealth> overview() {
        return monitoring.overview();
    }

    @GetMapping("/projects/{projectId}")
    public ProjectHealth projectStatus(@PathVariable UUID projectId) {
        return monitoring.projectStatus(projectId);
    }
}

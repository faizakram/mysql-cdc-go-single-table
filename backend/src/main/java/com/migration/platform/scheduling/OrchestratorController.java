package com.migration.platform.scheduling;

import com.migration.platform.scheduling.dto.OrchestratorDtos.OrchestratorStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the live job-queue / concurrency state (#54). */
@RestController
@RequestMapping("/api/v1/orchestrator")
public class OrchestratorController {

    private final JobOrchestrator orchestrator;

    public OrchestratorController(JobOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/status")
    public OrchestratorStatus status() {
        return orchestrator.status();
    }
}

package com.migration.platform.advisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Performance advisor for a project: tuning recommendations from live throughput + lag (#217). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/advisor")
public class AdvisorController {

    private final PerformanceAdvisorService advisor;

    public AdvisorController(PerformanceAdvisorService advisor) {
        this.advisor = advisor;
    }

    @GetMapping
    public AdvisorReport advise(@PathVariable UUID projectId) {
        return advisor.advise(projectId);
    }
}

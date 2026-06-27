package com.migration.platform.planning;

import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Intelligent migration plan for a project (#88). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/plan")
public class MigrationPlanController {

    private final MigrationPlanService service;

    public MigrationPlanController(MigrationPlanService service) {
        this.service = service;
    }

    @GetMapping
    public MigrationPlan plan(@PathVariable UUID projectId) {
        return service.plan(projectId);
    }
}

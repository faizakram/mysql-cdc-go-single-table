package com.migration.platform.intelligence;

import com.migration.platform.intelligence.MigrationIntelligence.CostEstimate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI-assisted migration intelligence (#108): cost, recommendations, remediation. */
@RestController
@RequestMapping("/api/v1")
public class IntelligenceController {

    private final IntelligenceService service;

    public IntelligenceController(IntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/cost-estimate")
    public CostEstimate cost(@PathVariable UUID projectId) {
        return service.cost(projectId);
    }

    @GetMapping("/projects/{projectId}/recommendations")
    public List<IntelligenceService.Recommendation> recommendations(@PathVariable UUID projectId) {
        return service.recommendations(projectId);
    }

    /** Stateless helper: suggest a remediation for an error message. */
    @PostMapping("/remediation")
    public Map<String, String> remediation(@RequestBody Map<String, String> body) {
        return Map.of("hint", MigrationIntelligence.remediation(body.get("error")));
    }
}

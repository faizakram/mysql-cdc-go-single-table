package com.migration.platform.job;

import com.migration.platform.job.dto.JobResponse;
import com.migration.platform.job.dto.TableStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/jobs")
    public List<JobResponse> listForProject(@PathVariable UUID projectId) {
        return service.listForProject(projectId);
    }

    @PostMapping("/projects/{projectId}/jobs")
    public ResponseEntity<JobResponse> create(@PathVariable UUID projectId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(projectId));
    }

    /** Preview the connector configs that would be deployed (secrets masked). */
    @GetMapping("/projects/{projectId}/connector-preview")
    public Map<String, Object> preview(@PathVariable UUID projectId) {
        return service.preview(projectId);
    }

    @GetMapping("/jobs/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Per-table status for a run, from the metadata store (#19). */
    @GetMapping("/jobs/{id}/tables")
    public List<TableStatusResponse> tables(@PathVariable UUID id) {
        return service.tablesForJob(id);
    }

    @PostMapping("/jobs/{id}/start")
    public JobResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PostMapping("/jobs/{id}/pause")
    public JobResponse pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @PostMapping("/jobs/{id}/resume")
    public JobResponse resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @PostMapping("/jobs/{id}/stop")
    public JobResponse stop(@PathVariable UUID id) {
        return service.stop(id);
    }

    /** Re-run a clean full load: reset source offsets so Debezium re-snapshots (#131). */
    @PostMapping("/jobs/{id}/reload")
    public JobResponse reload(@PathVariable UUID id) {
        return service.reloadFull(id);
    }
}

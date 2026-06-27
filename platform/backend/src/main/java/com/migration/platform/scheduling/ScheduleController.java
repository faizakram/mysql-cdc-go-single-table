package com.migration.platform.scheduling;

import com.migration.platform.scheduling.dto.ScheduleDtos.ScheduleRequest;
import com.migration.platform.scheduling.dto.ScheduleDtos.ScheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** CRUD + run-now for per-project job schedules (#53). */
@RestController
@RequestMapping("/api/v1")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/schedules")
    public List<ScheduleResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId).stream().map(ScheduleResponse::from).toList();
    }

    @PostMapping("/projects/{projectId}/schedules")
    public ResponseEntity<ScheduleResponse> create(@PathVariable UUID projectId,
                                                    @Valid @RequestBody ScheduleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ScheduleResponse.from(service.create(projectId, req)));
    }

    @PutMapping("/schedules/{id}")
    public ScheduleResponse update(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest req) {
        return ScheduleResponse.from(service.update(id, req));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{id}/run-now")
    public ScheduleResponse runNow(@PathVariable UUID id) {
        return ScheduleResponse.from(service.runNow(id));
    }
}

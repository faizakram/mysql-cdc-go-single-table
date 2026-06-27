package com.migration.platform.alert;

import com.migration.platform.alert.dto.AlertResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    public List<AlertResponse> list(@RequestParam(required = false) UUID projectId) {
        return service.list(projectId).stream().map(AlertResponse::from).toList();
    }

    /** Firing count for the UI badge. */
    @GetMapping("/count")
    public Map<String, Long> firingCount() {
        return Map.of("firing", service.firingCount());
    }

    @PostMapping("/{id}/acknowledge")
    public AlertResponse acknowledge(@PathVariable UUID id) {
        return AlertResponse.from(service.acknowledge(id));
    }
}

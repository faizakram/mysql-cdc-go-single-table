package com.migration.platform.quality;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Column profiling + PII flagging for a table (#112). */
@RestController
@RequestMapping("/api/v1/connections/{id}/profile")
public class DataQualityController {

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @GetMapping
    public DataQualityService.TableProfile profile(@PathVariable UUID id,
                                                   @RequestParam String schema,
                                                   @RequestParam String table) {
        return service.profile(id, schema, table);
    }
}

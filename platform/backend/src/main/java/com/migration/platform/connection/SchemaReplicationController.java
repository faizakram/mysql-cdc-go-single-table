package com.migration.platform.connection;

import com.migration.platform.connection.dto.ConstraintDtos.ConstraintApplyResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Replicate source indexes/FKs onto the target for a project (issue #33). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/schema/constraints")
public class SchemaReplicationController {

    private final SchemaReplicationService service;

    public SchemaReplicationController(SchemaReplicationService service) {
        this.service = service;
    }

    /** Preview the DDL (indexes then foreign keys) without applying. */
    @GetMapping("/ddl")
    public List<String> preview(@PathVariable UUID projectId) {
        return service.previewDdl(projectId);
    }

    /** Generate + apply the constraints on the target (idempotent). */
    @PostMapping("/apply")
    public ConstraintApplyResult apply(@PathVariable UUID projectId) {
        return service.apply(projectId);
    }
}

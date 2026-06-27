package com.migration.platform.connection;

import com.migration.platform.common.PageResponse;
import com.migration.platform.connection.dto.ConnectionRequest;
import com.migration.platform.connection.dto.ConnectionResponse;
import com.migration.platform.connection.dto.TestResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionService service;
    private final CdcReadinessService readiness;
    private final SchemaObjectService schemaObjects;

    public ConnectionController(ConnectionService service, CdcReadinessService readiness,
                                SchemaObjectService schemaObjects) {
        this.service = service;
        this.readiness = readiness;
        this.schemaObjects = schemaObjects;
    }

    @GetMapping
    public PageResponse<ConnectionResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DbType dbType) {
        return service.listPage(page, size, q, dbType);
    }

    /** The supported-engine catalog that drives the connection form + pair validation (#76/#82). */
    @GetMapping("/engines")
    public List<EngineCatalog.EngineSpec> engines() {
        return EngineCatalog.all();
    }

    /** Per-engine CDC prerequisite checks for a saved connection (#80). */
    @GetMapping("/{id}/cdc-readiness")
    public CdcReadinessService.Readiness cdcReadiness(@PathVariable UUID id) {
        return readiness.check(id);
    }

    /** Inventory of non-table schema objects: sequences/identity/views/procedures/functions (#92). */
    @GetMapping("/{id}/schema-objects")
    public SchemaObjectService.Inventory schemaObjects(@PathVariable UUID id) {
        return schemaObjects.inventory(id);
    }

    @GetMapping("/{id}")
    public ConnectionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> create(@Valid @RequestBody ConnectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ConnectionResponse update(@PathVariable UUID id, @Valid @RequestBody ConnectionRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Test a saved connection. */
    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable UUID id) {
        return service.test(id);
    }

    /** Test ad-hoc parameters from the form before saving. */
    @PostMapping("/test")
    public TestResult testAdhoc(@Valid @RequestBody ConnectionRequest req) {
        return service.testAdhoc(req);
    }
}

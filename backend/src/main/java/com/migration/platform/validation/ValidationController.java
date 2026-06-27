package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import com.migration.platform.validation.dto.ValidationDtos.ValidationReport;
import com.migration.platform.validation.dto.ValidationDtos.ValidationRunDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Advanced validation report + CSV export (#96), now job-based for scale (#150). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/validation")
public class ValidationController {

    private final ValidationService service;

    public ValidationController(ValidationService service) {
        this.service = service;
    }

    /** Enqueue a background validation run; returns immediately with the run (status PENDING/RUNNING). */
    @PostMapping
    public ValidationRunDto start(@PathVariable UUID projectId) {
        return service.start(projectId);
    }

    /** Most recent run for the project (with per-table results), or null if none started yet. */
    @GetMapping("/latest")
    public ValidationRunDto latest(@PathVariable UUID projectId) {
        return service.latest(projectId);
    }

    /** Run history (metadata only). */
    @GetMapping("/runs")
    public List<ValidationRunDto> runs(@PathVariable UUID projectId) {
        return service.history(projectId);
    }

    /** A single run with the results computed so far — polled by the UI for live progress. */
    @GetMapping("/runs/{runId}")
    public ValidationRunDto run(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.report(runId);
    }

    /** Legacy synchronous report (kept for backward compatibility / small projects). */
    @GetMapping
    public ValidationReport validate(@PathVariable UUID projectId) {
        return service.validate(projectId);
    }

    @GetMapping(value = "/report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@PathVariable UUID projectId) {
        ValidationReport r = service.validate(projectId);
        StringBuilder sb = new StringBuilder("schema,table,source_rows,target_rows,null_pk,duplicate_keys,missing,extra,cdc_inserts,cdc_updates,cdc_deletes,status,issues\n");
        for (TableValidation t : r.results()) {
            sb.append(t.schema()).append(',').append(t.table()).append(',')
              .append(t.sourceRows()).append(',').append(t.targetRows()).append(',')
              .append(t.nullPrimaryKey()).append(',').append(t.duplicateKeys()).append(',')
              .append(t.missingRows()).append(',').append(t.extraRows()).append(',')
              .append(t.cdcInserts()).append(',').append(t.cdcUpdates()).append(',').append(t.cdcDeletes()).append(',')
              .append(t.status()).append(',').append('"').append(String.join("; ", t.issues())).append('"').append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=validation-report.csv")
                .contentType(MediaType.valueOf("text/csv"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}

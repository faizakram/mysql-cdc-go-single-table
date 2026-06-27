package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import com.migration.platform.validation.dto.ValidationDtos.ValidationReport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Advanced validation report + CSV export (#96). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/validation")
public class ValidationController {

    private final ValidationService service;

    public ValidationController(ValidationService service) {
        this.service = service;
    }

    @GetMapping
    public ValidationReport validate(@PathVariable UUID projectId) {
        return service.validate(projectId);
    }

    @GetMapping(value = "/report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@PathVariable UUID projectId) {
        ValidationReport r = service.validate(projectId);
        StringBuilder sb = new StringBuilder("schema,table,source_rows,target_rows,null_pk,duplicate_keys,missing,extra,status,issues\n");
        for (TableValidation t : r.results()) {
            sb.append(t.schema()).append(',').append(t.table()).append(',')
              .append(t.sourceRows()).append(',').append(t.targetRows()).append(',')
              .append(t.nullPrimaryKey()).append(',').append(t.duplicateKeys()).append(',')
              .append(t.missingRows()).append(',').append(t.extraRows()).append(',')
              .append(t.status()).append(',').append('"').append(String.join("; ", t.issues())).append('"').append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=validation-report.csv")
                .contentType(MediaType.valueOf("text/csv"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}

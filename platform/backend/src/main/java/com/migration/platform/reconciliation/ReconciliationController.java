package com.migration.platform.reconciliation;

import com.migration.platform.reconciliation.dto.ReconciliationDtos.ResultDto;
import com.migration.platform.reconciliation.dto.ReconciliationDtos.RunDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Trigger a reconciliation run (synchronous). mode = COUNT (default) | CHECKSUM. */
    @PostMapping("/projects/{projectId}/reconciliation")
    public RunDto run(@PathVariable UUID projectId,
                      @RequestParam(defaultValue = "COUNT") String mode,
                      @RequestParam(defaultValue = "1000") int sampleSize) {
        return service.run(projectId, mode, sampleSize);
    }

    /** Past runs with their per-table results (drift history). */
    @GetMapping("/projects/{projectId}/reconciliation")
    public List<RunDto> history(@PathVariable UUID projectId) {
        return service.history(projectId);
    }

    /** Downloadable CSV report for a single run (#49). */
    @GetMapping(value = "/reconciliation/{runId}/report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> report(@PathVariable UUID runId) {
        RunDto run = service.report(runId);
        StringBuilder sb = new StringBuilder("schema,table,status,source_count,target_count,difference,sampled,missing,error\n");
        for (ResultDto r : run.results()) {
            sb.append(csv(r.schemaName())).append(',').append(csv(r.tableName())).append(',')
              .append(csv(r.status())).append(',').append(n(r.sourceCount())).append(',')
              .append(n(r.targetCount())).append(',').append(n(r.difference())).append(',')
              .append(n(r.sampled())).append(',').append(n(r.missing())).append(',')
              .append(csv(r.error())).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reconciliation-" + runId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private static String n(Long v) {
        return v == null ? "" : v.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}

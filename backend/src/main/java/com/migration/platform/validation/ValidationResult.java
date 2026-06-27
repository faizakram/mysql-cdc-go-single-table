package com.migration.platform.validation;

import com.migration.platform.validation.dto.ValidationDtos.TableValidation;
import jakarta.persistence.*;

import java.util.List;
import java.util.UUID;

/**
 * One table's integrity result within a {@link ValidationRun} (#150). Persisted as soon as the
 * table finishes so partial results are visible while the rest of the run is still computing.
 */
@Entity
@Table(name = "validation_result")
public class ValidationResult {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "source_rows")
    private Long sourceRows;

    @Column(name = "target_rows")
    private Long targetRows;

    @Column(name = "null_primary_key")
    private Long nullPrimaryKey;

    @Column(name = "duplicate_keys")
    private Long duplicateKeys;

    @Column(name = "missing_rows")
    private Long missingRows;

    @Column(name = "extra_rows")
    private Long extraRows;

    @Column(name = "cdc_inserts")
    private Long cdcInserts;

    @Column(name = "cdc_updates")
    private Long cdcUpdates;

    @Column(name = "cdc_deletes")
    private Long cdcDeletes;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "text")
    private String issues;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
    }

    /** Build a persistable result from the computed {@link TableValidation} record. */
    static ValidationResult from(UUID runId, TableValidation t) {
        ValidationResult r = new ValidationResult();
        r.runId = runId;
        r.schemaName = t.schema();
        r.tableName = t.table();
        r.sourceRows = t.sourceRows();
        r.targetRows = t.targetRows();
        r.nullPrimaryKey = t.nullPrimaryKey();
        r.duplicateKeys = t.duplicateKeys();
        r.missingRows = t.missingRows();
        r.extraRows = t.extraRows();
        r.cdcInserts = t.cdcInserts();
        r.cdcUpdates = t.cdcUpdates();
        r.cdcDeletes = t.cdcDeletes();
        r.status = t.status();
        r.issues = t.issues() == null ? "" : String.join("; ", t.issues());
        return r;
    }

    /** Read model for the API, mirroring the synchronous report's row shape. */
    TableValidation toDto() {
        List<String> issueList = (issues == null || issues.isBlank())
                ? List.of() : List.of(issues.split("; "));
        return new TableValidation(
                schemaName, tableName,
                z(sourceRows), z(targetRows), z(nullPrimaryKey), z(duplicateKeys),
                z(missingRows), z(extraRows), z(cdcInserts), z(cdcUpdates), z(cdcDeletes),
                status, issueList);
    }

    private static long z(Long v) { return v == null ? 0 : v; }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getSchemaName() { return schemaName; }
    public String getTableName() { return tableName; }
    public String getStatus() { return status; }
}

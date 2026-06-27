package com.migration.platform.reconciliation;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "reconciliation_result")
public class ReconciliationResult {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "source_count")
    private Long sourceCount;

    @Column(name = "target_count")
    private Long targetCount;

    private Long difference;

    /** CHECKSUM mode: rows sampled from source, how many PKs were absent in target, and how many
     *  present-on-both rows had differing content (value mismatch). */
    private Long sampled;
    private Long missing;
    private Long changed;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "text")
    private String error;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public Long getSourceCount() { return sourceCount; }
    public void setSourceCount(Long sourceCount) { this.sourceCount = sourceCount; }
    public Long getTargetCount() { return targetCount; }
    public void setTargetCount(Long targetCount) { this.targetCount = targetCount; }
    public Long getDifference() { return difference; }
    public void setDifference(Long difference) { this.difference = difference; }
    public Long getSampled() { return sampled; }
    public void setSampled(Long sampled) { this.sampled = sampled; }
    public Long getMissing() { return missing; }
    public void setMissing(Long missing) { this.missing = missing; }
    public Long getChanged() { return changed; }
    public void setChanged(Long changed) { this.changed = changed; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}

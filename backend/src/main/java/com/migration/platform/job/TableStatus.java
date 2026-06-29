package com.migration.platform.job;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Per-table status for a migration job, persisted in the metadata store (replaces JSON, #19). */
@Entity
@Table(name = "table_status")
public class TableStatus {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String phase;   // SCHEMA | DATA | CDC

    @Column(nullable = false)
    private String status;  // PENDING | IN_PROGRESS | COMPLETED | FAILED

    @Column(name = "rows_synced", nullable = false)
    private long rowsSynced;

    // Snapshot progress / ETA / lag (#185). Nullable: unknown until estimated / not applicable.
    @Column(name = "total_rows")
    private Long totalRows;            // source row-count estimate (catalog stats) for %-complete / ETA

    @Column(name = "last_lag_ms")
    private Long lastLagMs;            // most recent per-table replication lag (CDC tables)

    @Column(name = "started_at")
    private OffsetDateTime startedAt;  // when this table's sync began (per-table throughput)

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null) id = UUID.randomUUID();
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getRowsSynced() { return rowsSynced; }
    public void setRowsSynced(long rowsSynced) { this.rowsSynced = rowsSynced; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Long getTotalRows() { return totalRows; }
    public void setTotalRows(Long totalRows) { this.totalRows = totalRows; }
    public Long getLastLagMs() { return lastLagMs; }
    public void setLastLagMs(Long lastLagMs) { this.lastLagMs = lastLagMs; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
}

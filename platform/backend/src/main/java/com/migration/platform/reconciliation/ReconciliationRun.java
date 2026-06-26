package com.migration.platform.reconciliation;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String status;

    @Column(name = "total_tables", nullable = false)
    private int totalTables;

    @Column(nullable = false)
    private int mismatched;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (startedAt == null) startedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalTables() { return totalTables; }
    public void setTotalTables(int totalTables) { this.totalTables = totalTables; }
    public int getMismatched() { return mismatched; }
    public void setMismatched(int mismatched) { this.mismatched = mismatched; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}

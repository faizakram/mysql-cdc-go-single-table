package com.migration.platform.reconciliation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "reconciliation_result")
@Getter
@Setter
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

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "text")
    private String error;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
    }
}

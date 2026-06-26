package com.migration.platform.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    List<ReconciliationRun> findByProjectIdOrderByStartedAtDesc(UUID projectId);
}

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, UUID> {
    List<ReconciliationResult> findByRunIdOrderByTableName(UUID runId);
}

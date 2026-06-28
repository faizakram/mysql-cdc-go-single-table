package com.migration.platform.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TableStatusRepository extends JpaRepository<TableStatus, UUID> {
    List<TableStatus> findByJobIdOrderByTableName(UUID jobId);
    void deleteByJobId(UUID jobId);

    /** Single-row lookup for per-table progress updates — avoids loading every row of the job (#213). */
    Optional<TableStatus> findByJobIdAndTableNameIgnoreCase(UUID jobId, String tableName);
}

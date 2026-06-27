package com.migration.platform.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TableStatusRepository extends JpaRepository<TableStatus, UUID> {
    List<TableStatus> findByJobIdOrderByTableName(UUID jobId);
    void deleteByJobId(UUID jobId);
}

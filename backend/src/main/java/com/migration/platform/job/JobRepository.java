package com.migration.platform.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<MigrationJob, UUID> {
    List<MigrationJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    long countByStatusIn(Collection<JobStatus> statuses);

    List<MigrationJob> findByStatusIn(Collection<JobStatus> statuses);

    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<JobStatus> statuses);
}

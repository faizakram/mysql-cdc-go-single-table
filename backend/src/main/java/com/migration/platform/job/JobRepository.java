package com.migration.platform.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<MigrationJob, UUID> {
    List<MigrationJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    long countByStatusIn(Collection<JobStatus> statuses);

    List<MigrationJob> findByStatusIn(Collection<JobStatus> statuses);

    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<JobStatus> statuses);

    /**
     * Jobs that have at least one deployed connector, newest first — the dashboard overview derives the
     * monitored projects from this instead of scanning every project and querying jobs per project (#214).
     */
    @Query("select j from MigrationJob j where j.sourceConnectorName is not null "
            + "or j.sinkConnectorName is not null order by j.createdAt desc")
    List<MigrationJob> findWithConnectorsOrderByCreatedAtDesc();
}

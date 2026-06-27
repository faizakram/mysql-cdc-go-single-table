package com.migration.platform.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface JobScheduleRepository extends JpaRepository<JobSchedule, UUID> {

    List<JobSchedule> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Enabled schedules whose next run is due. */
    List<JobSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(OffsetDateTime now);
}

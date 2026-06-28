package com.migration.platform.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<MigrationProject, UUID>,
        JpaSpecificationExecutor<MigrationProject> {
    boolean existsByName(String name);

    /** Projects in a given lifecycle state — lets schedulers/monitors work on ACTIVE only, not findAll() (#214). */
    List<MigrationProject> findByStatus(ProjectStatus status);

    /** Projects referencing a connection as source or target — used to block edit/delete of in-use connections (#179). */
    List<MigrationProject> findBySourceConnectionIdOrTargetConnectionId(UUID sourceConnectionId, UUID targetConnectionId);
}

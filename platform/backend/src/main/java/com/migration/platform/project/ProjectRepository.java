package com.migration.platform.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<MigrationProject, UUID>,
        JpaSpecificationExecutor<MigrationProject> {
    boolean existsByName(String name);
}

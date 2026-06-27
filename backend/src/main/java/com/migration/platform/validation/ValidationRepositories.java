package com.migration.platform.validation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ValidationRunRepository extends JpaRepository<ValidationRun, UUID> {
    List<ValidationRun> findByProjectIdOrderByStartedAtDesc(UUID projectId);
    Optional<ValidationRun> findFirstByProjectIdOrderByStartedAtDesc(UUID projectId);
}

interface ValidationResultRepository extends JpaRepository<ValidationResult, UUID> {
    List<ValidationResult> findByRunIdOrderBySchemaNameAscTableNameAsc(UUID runId);
}

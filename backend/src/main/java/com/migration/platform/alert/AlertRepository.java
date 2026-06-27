package com.migration.platform.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    Optional<Alert> findFirstByDedupKeyAndStatus(String dedupKey, String status);
    List<Alert> findTop200ByOrderByCreatedAtDesc();
    List<Alert> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    long countByStatus(String status);
}

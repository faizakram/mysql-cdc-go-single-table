package com.migration.platform.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<DbConnection, UUID>,
        JpaSpecificationExecutor<DbConnection> {
    boolean existsByName(String name);
}

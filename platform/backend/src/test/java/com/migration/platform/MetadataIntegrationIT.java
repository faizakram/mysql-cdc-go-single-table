package com.migration.platform;

import com.migration.platform.common.CryptoService;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.ConnectionService;
import com.migration.platform.connection.dto.ConnectionRequest;
import com.migration.platform.connection.dto.ConnectionResponse;
import com.migration.platform.project.ProjectService;
import com.migration.platform.project.dto.ProjectRequest;
import com.migration.platform.project.dto.ProjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end metadata-layer integration test against a real PostgreSQL (Flyway migrations + JPA).
 * Named *IT so it is NOT run by surefire (`mvn test`); it requires Docker. This is the seed for the
 * full Dockerised pipeline test (MSSQL → Kafka → PG) tracked under #58 — to be run via failsafe/CI.
 */
@SpringBootTest
@Testcontainers
class MetadataIntegrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired ConnectionService connectionService;
    @Autowired ProjectService projectService;
    @Autowired ConnectionRepository connectionRepository;
    @Autowired CryptoService crypto;

    @Test
    void persistsConnectionWithEncryptedSecretAndLinksProject() {
        ConnectionResponse src = connectionService.create(new ConnectionRequest(
                "it-source", DbType.SQLSERVER, "mssql", 1433, "Employees", "sa", "S3cret!", Map.of()));
        ConnectionResponse tgt = connectionService.create(new ConnectionRequest(
                "it-target", DbType.POSTGRESQL, "pg", 5432, "target_db", "postgres", "pgpw", Map.of()));

        // Secret is encrypted at rest and never present on the response (#43).
        var stored = connectionRepository.findById(src.id()).orElseThrow();
        assertThat(stored.getPasswordEnc()).isNotEqualTo("S3cret!");
        assertThat(crypto.decrypt(stored.getPasswordEnc())).isEqualTo("S3cret!");

        ProjectResponse project = projectService.create(new ProjectRequest(
                "it-project", "integration", src.id(), tgt.id(),
                Map.of("deleteStrategy", "SOFT", "selectedTables", java.util.List.of("dbo.Employees"))));

        assertThat(project.status().name()).isEqualTo("READY"); // both endpoints set
        assertThat(projectService.list()).extracting(ProjectResponse::name).contains("it-project");
    }
}

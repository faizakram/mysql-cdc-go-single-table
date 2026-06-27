package com.migration.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the generated OpenAPI contract on a running app (#24). Confirms the spec is served
 * without auth and advertises the core endpoints. Runs in CI (Docker); skipped otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "platform.metadata.embedded=false") // use the Testcontainers Postgres
@Testcontainers(disabledWithoutDocker = true)
class OpenApiContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestRestTemplate rest;

    @Test
    void openApiSpecIsServedAndDescribesCoreEndpoints() {
        String spec = rest.getForObject("/v3/api-docs", String.class);
        assertThat(spec).isNotNull();
        assertThat(spec).contains("\"openapi\"");
        assertThat(spec).contains("/api/v1/projects");
        assertThat(spec).contains("/api/v1/connections");
        assertThat(spec).contains("/api/v1/auth/login");
        assertThat(spec).contains("/api/v1/projects/{projectId}/reconciliation");
    }

    @Test
    void protectedEndpointReturnsApiErrorEnvelopeWhenUnauthenticated() {
        // No bearer token → 401 with the standard error envelope (#24).
        var response = rest.getForEntity("/api/v1/projects", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("\"status\":401", "\"error\":\"Unauthorized\"");
    }
}

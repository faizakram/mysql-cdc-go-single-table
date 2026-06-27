package com.migration.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI platformOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Heterogeneous Database Migration (CDC) — Control Plane API")
                .description("Manage migration projects, connections, and CDC jobs over Debezium/Kafka.")
                .version("v1")
                .license(new License().name("Internal")));
    }
}

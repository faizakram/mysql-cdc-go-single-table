package com.migration.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly-typed binding for the {@code platform.*} configuration tree. */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(Connect connect, Crypto crypto, Cors cors, Auth auth) {

    public record Connect(String baseUrl, String kafkaBootstrap, String username, String password) {}

    public record Crypto(String key) {}

    public record Cors(String allowedOrigins) {}

    public record Auth(String jwtSecret, long ttlMinutes, String adminUsername, String adminPassword) {}
}

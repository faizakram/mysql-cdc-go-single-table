package com.migration.platform.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how DB passwords are placed into generated connector configs (#43).
 *
 * <ul>
 *   <li>{@code inline} (default) — the decrypted password is written into the connector config.
 *       Simple; relies on Connect REST being locked down (#45). Fine for local/dev.</li>
 *   <li>{@code file} — the connector config emits a {@code ${file:...}} reference resolved at runtime
 *       by Kafka Connect's {@code FileConfigProvider}. The actual secret is mounted into the Connect
 *       workers from a secrets manager / K8s secret, so it never appears in the config topic, the
 *       REST API, or on the platform's disk.</li>
 *   <li>{@code env} — emits a {@code ${env:VAR}} reference resolved by {@code EnvVarConfigProvider}.</li>
 * </ul>
 *
 * For {@code file}/{@code env} the Connect worker must declare the matching provider, e.g.
 * {@code config.providers=file,env} with the corresponding provider classes. See
 * {@code platform/docs/SECRETS.md}.
 *
 * @param mode     inline | file | env
 * @param filePath base path (file mode) where per-connection secret property files are mounted
 * @param envPrefix env-var name prefix (env mode)
 */
@ConfigurationProperties(prefix = "platform.connect.secrets")
public record ConnectorSecretProperties(String mode, String filePath, String envPrefix) {

    public ConnectorSecretProperties {
        if (mode == null || mode.isBlank()) mode = "inline";
        if (filePath == null || filePath.isBlank()) filePath = "/opt/connect-secrets";
        if (envPrefix == null || envPrefix.isBlank()) envPrefix = "CDC_SECRET_";
    }

    public boolean inline() {
        return "inline".equalsIgnoreCase(mode);
    }

    /**
     * Resolve the value to place in the connector config for a given role's password.
     * In inline mode this is the plaintext; otherwise a provider reference Connect resolves at runtime.
     *
     * @param role      "source" or "sink" — keeps the reference stable + readable
     * @param plaintext the decrypted password (used only in inline mode)
     */
    public String passwordValue(String role, String plaintext) {
        return switch (mode.toLowerCase()) {
            case "file" -> "${file:" + filePath + "/" + role + ".properties:password}";
            case "env" -> "${env:" + envPrefix + role.toUpperCase() + "_PASSWORD}";
            default -> plaintext;
        };
    }
}

package com.migration.platform.connect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin client over the Kafka Connect REST API. All data-plane (connector) management goes
 * through the control plane rather than direct, unauthenticated calls to :8083 (issue #45).
 *
 * <p>Calls are wrapped in a bounded retry with backoff (#177) so a transient Connect blip — I/O
 * error, timeout, or 5xx during a rebalance — doesn't immediately fail a job. Non-transient
 * responses (4xx such as 404/409) are not retried and propagate to the caller.
 */
@Component
public class KafkaConnectClient {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnectClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 300;

    private final RestClient client;

    public KafkaConnectClient(RestClient connectRestClient) {
        this.client = connectRestClient;
    }

    /** Single-shot reachability probe for the health indicator (no retry); returns the Connect version. */
    public String ping() {
        Map<String, Object> root = client.get().uri("/")
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return root == null ? "unknown" : String.valueOf(root.get("version"));
    }

    public List<String> listConnectors() {
        return withRetry(() -> client.get().uri("/connectors")
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {}));
    }

    public Map<String, Object> connectorStatus(String name) {
        return withRetry(() -> client.get().uri("/connectors/{name}/status", name)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
    }

    /**
     * Committed source offsets for a connector (Kafka Connect 3.6+). For a Debezium source the offset
     * payload carries the snapshot flag, which lets us tell snapshot-in-progress from streaming.
     * Returns an empty map if the running Connect build doesn't expose the endpoint or it errors.
     */
    public Map<String, Object> connectorOffsets(String name) {
        try {
            return withRetry(() -> client.get().uri("/connectors/{name}/offsets", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> createConnector(Map<String, Object> config) {
        return withRetry(() -> client.post().uri("/connectors")
                .body(config)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
    }

    public void pause(String name) {
        withRetry(() -> client.put().uri("/connectors/{name}/pause", name).retrieve().toBodilessEntity());
    }

    public void resume(String name) {
        withRetry(() -> client.put().uri("/connectors/{name}/resume", name).retrieve().toBodilessEntity());
    }

    public void restart(String name) {
        // Explicit JSON content type: these are bodiless POSTs, and SimpleClientHttpRequestFactory
        // otherwise defaults to application/x-www-form-urlencoded, which Connect's restart endpoints
        // reject with HTTP 415 — so auto-restart of a FAILED task silently never recovered it (#202).
        withRetry(() -> client.post().uri("/connectors/{name}/restart", name)
                .contentType(MediaType.APPLICATION_JSON).retrieve().toBodilessEntity());
    }

    /** Restart a specific connector task (Kafka Connect) — used to recover a FAILED sink task (#176). */
    public void restartTask(String name, int taskId) {
        withRetry(() -> client.post().uri("/connectors/{name}/tasks/{taskId}/restart", name, taskId)
                .contentType(MediaType.APPLICATION_JSON).retrieve().toBodilessEntity());
    }

    /** Transition a connector to the STOPPED state (Kafka Connect 3.5+) — keeps config, releases tasks. */
    public void stop(String name) {
        withRetry(() -> client.put().uri("/connectors/{name}/stop", name).retrieve().toBodilessEntity());
    }

    /**
     * Delete a connector's committed source offsets (Kafka Connect 3.6+). The connector must be in the
     * STOPPED state. With offsets cleared, a Debezium source re-runs its initial snapshot on resume —
     * this is what powers "Re-run full load" (#131).
     */
    public void deleteOffsets(String name) {
        withRetry(() -> client.delete().uri("/connectors/{name}/offsets", name).retrieve().toBodilessEntity());
    }

    public void delete(String name) {
        withRetry(() -> client.delete().uri("/connectors/{name}", name).retrieve().toBodilessEntity());
    }

    /**
     * Run {@code op}, retrying only on transient failures (I/O error, timeout, or 5xx) with linear
     * backoff. 4xx responses (404, 409 "already exists", etc.) are not transient and propagate.
     */
    private <T> T withRetry(Supplier<T> op) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return op.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("Kafka Connect call failed (attempt {}/{}): {} — retrying",
                            attempt, MAX_ATTEMPTS, e.getMessage());
                    try { Thread.sleep(BASE_BACKOFF_MS * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last;
    }
}

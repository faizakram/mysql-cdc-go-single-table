package com.migration.platform.connect;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin client over the Kafka Connect REST API. All data-plane (connector) management goes
 * through the control plane rather than direct, unauthenticated calls to :8083 (issue #45).
 */
@Component
public class KafkaConnectClient {

    private final RestClient client;

    public KafkaConnectClient(RestClient connectRestClient) {
        this.client = connectRestClient;
    }

    public List<String> listConnectors() {
        return client.get().uri("/connectors")
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
    }

    public Map<String, Object> connectorStatus(String name) {
        return client.get().uri("/connectors/{name}/status", name)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * Committed source offsets for a connector (Kafka Connect 3.6+). For a Debezium source the offset
     * payload carries the snapshot flag, which lets us tell snapshot-in-progress from streaming.
     * Returns an empty map if the running Connect build doesn't expose the endpoint or it errors.
     */
    public Map<String, Object> connectorOffsets(String name) {
        try {
            return client.get().uri("/connectors/{name}/offsets", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> createConnector(Map<String, Object> config) {
        return client.post().uri("/connectors")
                .body(config)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public void pause(String name) {
        client.put().uri("/connectors/{name}/pause", name).retrieve().toBodilessEntity();
    }

    public void resume(String name) {
        client.put().uri("/connectors/{name}/resume", name).retrieve().toBodilessEntity();
    }

    public void restart(String name) {
        client.post().uri("/connectors/{name}/restart", name).retrieve().toBodilessEntity();
    }

    public void delete(String name) {
        client.delete().uri("/connectors/{name}", name).retrieve().toBodilessEntity();
    }
}

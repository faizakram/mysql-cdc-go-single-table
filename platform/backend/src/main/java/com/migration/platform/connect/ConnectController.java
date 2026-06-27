package com.migration.platform.connect;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read/operate Kafka Connect connectors via the control plane. */
@RestController
@RequestMapping("/api/v1/connect")
public class ConnectController {

    private final KafkaConnectClient connect;

    public ConnectController(KafkaConnectClient connect) {
        this.connect = connect;
    }

    @GetMapping("/connectors")
    public List<String> list() {
        return connect.listConnectors();
    }

    @GetMapping("/connectors/{name}/status")
    public Map<String, Object> status(@PathVariable String name) {
        return connect.connectorStatus(name);
    }

    @PutMapping("/connectors/{name}/pause")
    public void pause(@PathVariable String name) {
        connect.pause(name);
    }

    @PutMapping("/connectors/{name}/resume")
    public void resume(@PathVariable String name) {
        connect.resume(name);
    }

    @PostMapping("/connectors/{name}/restart")
    public void restart(@PathVariable String name) {
        connect.restart(name);
    }
}

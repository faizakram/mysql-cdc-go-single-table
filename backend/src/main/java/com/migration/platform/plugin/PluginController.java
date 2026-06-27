package com.migration.platform.plugin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lists the extensions (plugins) loaded in this deployment (#116). */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private final PluginRegistry registry;

    public PluginController(PluginRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<PluginRegistry.PluginInfo> list() {
        return registry.list();
    }
}

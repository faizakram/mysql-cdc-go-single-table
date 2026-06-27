package com.migration.platform.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

public record ProjectRequest(
        @NotBlank String name,
        String description,
        UUID sourceConnectionId,
        UUID targetConnectionId,
        Map<String, Object> config
) {}

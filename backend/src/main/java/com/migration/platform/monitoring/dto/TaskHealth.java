package com.migration.platform.monitoring.dto;

public record TaskHealth(int id, String state, String workerId, String trace) {}

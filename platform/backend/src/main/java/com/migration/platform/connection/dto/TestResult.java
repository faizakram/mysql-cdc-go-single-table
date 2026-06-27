package com.migration.platform.connection.dto;

public record TestResult(boolean success, String message, Long latencyMs) {}

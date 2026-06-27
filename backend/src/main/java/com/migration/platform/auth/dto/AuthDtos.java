package com.migration.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, String username, String role, long expiresInMinutes) {}

    public record MeResponse(String username, String role) {}

    private AuthDtos() {}
}

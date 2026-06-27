package com.migration.platform.auth.dto;

import com.migration.platform.auth.AppUser;
import com.migration.platform.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class UserDtos {

    public record UserDto(UUID id, String username, Role role, boolean enabled, OffsetDateTime createdAt) {
        public static UserDto from(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), u.getCreatedAt());
        }
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, @NotNull Role role) {}

    /** All fields optional — only the provided ones are changed. */
    public record UpdateUserRequest(Role role, Boolean enabled, String password) {}

    private UserDtos() {}
}

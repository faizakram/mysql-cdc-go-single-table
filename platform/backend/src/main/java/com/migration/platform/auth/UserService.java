package com.migration.platform.auth;

import com.migration.platform.auth.dto.UserDtos.CreateUserRequest;
import com.migration.platform.auth.dto.UserDtos.UpdateUserRequest;
import com.migration.platform.auth.dto.UserDtos.UserDto;
import com.migration.platform.common.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** ADMIN-only user administration (#56): list users and assign roles / enablement. */
@Service
public class UserService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return repo.findAll().stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        if (repo.existsByUsername(req.username())) {
            throw new IllegalArgumentException("Username '" + req.username() + "' already exists");
        }
        AppUser u = new AppUser();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role());
        return UserDto.from(repo.save(u));
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest req, String actingUsername) {
        AppUser u = repo.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));

        boolean losingAdmin = u.getRole() == Role.ADMIN
                && ((req.role() != null && req.role() != Role.ADMIN) || Boolean.FALSE.equals(req.enabled()));
        if (losingAdmin && repo.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new IllegalArgumentException("Cannot remove the last active administrator");
        }
        if (u.getUsername().equals(actingUsername) && Boolean.FALSE.equals(req.enabled())) {
            throw new IllegalArgumentException("You cannot disable your own account");
        }

        if (req.role() != null) u.setRole(req.role());
        if (req.enabled() != null) u.setEnabled(req.enabled());
        if (req.password() != null && !req.password().isBlank()) u.setPasswordHash(encoder.encode(req.password()));
        return UserDto.from(repo.save(u));
    }

    @Transactional
    public void delete(UUID id, String actingUsername) {
        AppUser u = repo.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));
        if (u.getUsername().equals(actingUsername)) {
            throw new IllegalArgumentException("You cannot delete your own account");
        }
        if (u.getRole() == Role.ADMIN && u.isEnabled() && repo.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new IllegalArgumentException("Cannot delete the last active administrator");
        }
        repo.deleteById(id);
    }
}

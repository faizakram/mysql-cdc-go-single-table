package com.migration.platform.auth;

import com.migration.platform.auth.dto.UserDtos.CreateUserRequest;
import com.migration.platform.auth.dto.UserDtos.UpdateUserRequest;
import com.migration.platform.auth.dto.UserDtos.UserDto;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    /** Paged + filterable user list (#127). {@code q} matches username; {@code role}/{@code enabled} exact. */
    @Transactional(readOnly = true)
    public PageResponse<UserDto> listPage(int page, int size, String q, Role role, Boolean enabled) {
        Specification<AppUser> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("username")), "%" + q.toLowerCase() + "%"));
            }
            if (role != null) ps.add(cb.equal(root.get("role"), role));
            if (enabled != null) ps.add(cb.equal(root.get("enabled"), enabled));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.ASC, "username"));
        return PageResponse.of(repo.findAll(spec, pageable), UserDto::from);
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

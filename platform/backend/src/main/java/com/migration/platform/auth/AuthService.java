package com.migration.platform.auth;

import com.migration.platform.auth.dto.AuthDtos.LoginRequest;
import com.migration.platform.auth.dto.AuthDtos.LoginResponse;
import com.migration.platform.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final PlatformProperties props;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, PlatformProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }

    public LoginResponse login(LoginRequest req) {
        AppUser user = users.findByUsername(req.username())
                .filter(AppUser::isEnabled)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        String token = jwt.generate(user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole().name(), jwt.ttlMinutes());
    }

    /** Create the initial admin only when there are no users yet. */
    @Bean
    ApplicationRunner bootstrapAdmin() {
        return args -> {
            if (users.count() > 0) return;
            AppUser admin = new AppUser();
            admin.setUsername(props.auth().adminUsername());
            admin.setPasswordHash(encoder.encode(props.auth().adminPassword()));
            admin.setRole(Role.ADMIN);
            users.save(admin);
            log.warn("Created initial admin user '{}'. CHANGE THE PASSWORD immediately (set ADMIN_PASSWORD / rotate).",
                    admin.getUsername());
        };
    }
}

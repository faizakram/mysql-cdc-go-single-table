package com.migration.platform.auth;

import com.migration.platform.auth.dto.AuthDtos.LoginRequest;
import com.migration.platform.auth.dto.AuthDtos.LoginResponse;
import com.migration.platform.auth.dto.AuthDtos.MeResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    /** Exchange a still-valid token for a fresh one (#55), extending the session without re-login. */
    @PostMapping("/refresh")
    public LoginResponse refresh(Authentication authentication) {
        return auth.refresh(authentication.getName());
    }

    /** Current authenticated principal (from the validated JWT). */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        String role = authentication.getAuthorities().stream().findFirst()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).orElse("UNKNOWN");
        return new MeResponse(authentication.getName(), role);
    }
}

package com.migration.platform.auth;

import com.migration.platform.config.PlatformProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwt(String secret) {
        return new JwtService(new PlatformProperties(
                new PlatformProperties.Connect("u", "k", null, null),
                new PlatformProperties.Crypto("x"), new PlatformProperties.Cors("*"),
                new PlatformProperties.Auth(secret, 60, "a", "b")));
    }

    private final JwtService service = jwt("0123456789012345678901234567890123");

    @Test
    void generatesAndParsesTokenWithSubjectAndRole() {
        String token = service.generate("alice", Role.OPERATOR);
        Claims claims = service.parse(token);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("OPERATOR");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() {
        String token = jwt("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").generate("bob", Role.ADMIN);
        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> jwt("tooshort")).isInstanceOf(IllegalStateException.class);
    }
}

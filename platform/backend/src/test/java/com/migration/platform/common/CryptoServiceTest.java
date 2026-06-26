package com.migration.platform.common;

import com.migration.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private PlatformProperties props(String base64Key) {
        return new PlatformProperties(
                new PlatformProperties.Connect("u", "k", null, null),
                new PlatformProperties.Crypto(base64Key),
                new PlatformProperties.Cors("*"),
                new PlatformProperties.Auth("s", 1, "a", "b"));
    }

    private final String key32 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptDecryptRoundTrips() {
        CryptoService crypto = new CryptoService(props(key32));
        String secret = "P@ssw0rd-with-üñïçödé";
        String enc = crypto.encrypt(secret);
        assertThat(enc).isNotEqualTo(secret);
        assertThat(crypto.decrypt(enc)).isEqualTo(secret);
    }

    @Test
    void everyEncryptionUsesAFreshIv() {
        CryptoService crypto = new CryptoService(props(key32));
        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    void rejectsKeyThatIsNot32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(props(shortKey)))
                .isInstanceOf(IllegalStateException.class);
    }
}

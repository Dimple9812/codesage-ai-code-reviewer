package com.codesage.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.codesage.config.AppProperties;
import com.codesage.support.TestProps;

class TokenCipherTest {

    private final TokenCipher cipher = new TokenCipher(TestProps.defaults());

    @Test
    void encryptsAndDecryptsRoundTrip() {
        String encrypted = cipher.encrypt("gho_secretTokenValue");
        assertThat(encrypted).startsWith("v1:").doesNotContain("gho_secretTokenValue");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("gho_secretTokenValue");
    }

    @Test
    void sameInputProducesDifferentCiphertexts() {
        assertThat(cipher.encrypt("abc")).isNotEqualTo(cipher.encrypt("abc"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String encrypted = cipher.encrypt("value");
        char last = encrypted.charAt(encrypted.length() - 2);
        String tampered = encrypted.substring(0, encrypted.length() - 2) + (last == 'A' ? 'B' : 'A')
                + encrypted.charAt(encrypted.length() - 1);
        assertThat(cipher.decrypt(tampered)).isNull();
    }

    @Test
    void differentKeyCannotDecrypt() {
        var d = TestProps.defaults();
        TokenCipher other = new TokenCipher(new AppProperties(d.publicUrl(), d.frontendUrl(), d.corsAllowedOrigins(),
                "another-key", d.github(), d.ai(), d.review(), d.analysis(), d.rateLimit()));
        assertThat(other.decrypt(cipher.encrypt("value"))).isNull();
    }

    @Test
    void nullAndGarbageAreHandled() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("not-encrypted")).isNull();
        assertThat(cipher.decrypt("v1:!!!")).isNull();
    }
}

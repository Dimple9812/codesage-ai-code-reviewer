package com.codesage.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsValidSignature() {
        String header = WebhookSignatureVerifier.signatureHeader("s3cret", BODY);
        assertThat(WebhookSignatureVerifier.isValid("s3cret", BODY, header)).isTrue();
    }

    @Test
    void matchesGitHubDocumentedExample() {
        // Example from GitHub docs: secret "It's a Secret to Everybody", payload "Hello, World!"
        String expected = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        assertThat(WebhookSignatureVerifier.isValid("It's a Secret to Everybody",
                "Hello, World!".getBytes(StandardCharsets.UTF_8), expected)).isTrue();
    }

    @Test
    void rejectsWrongSecretTamperedBodyAndMalformedHeaders() {
        String header = WebhookSignatureVerifier.signatureHeader("s3cret", BODY);
        assertThat(WebhookSignatureVerifier.isValid("other", BODY, header)).isFalse();
        assertThat(WebhookSignatureVerifier.isValid("s3cret", "{}".getBytes(StandardCharsets.UTF_8), header)).isFalse();
        assertThat(WebhookSignatureVerifier.isValid("s3cret", BODY, null)).isFalse();
        assertThat(WebhookSignatureVerifier.isValid("s3cret", BODY, "sha1=abcdef")).isFalse();
        assertThat(WebhookSignatureVerifier.isValid("s3cret", BODY, "sha256=zz-not-hex")).isFalse();
        assertThat(WebhookSignatureVerifier.isValid("", BODY, header)).isFalse();
    }
}

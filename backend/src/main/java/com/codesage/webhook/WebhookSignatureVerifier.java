package com.codesage.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies GitHub's X-Hub-Signature-256 header (HMAC-SHA256 of the raw body) in constant time. */
public final class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private WebhookSignatureVerifier() {
    }

    public static boolean isValid(String secret, byte[] payload, String signatureHeader) {
        if (secret == null || secret.isBlank() || payload == null || signatureHeader == null
                || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, payload), provided);
    }

    public static byte[] sign(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String signatureHeader(String secret, byte[] payload) {
        return PREFIX + HexFormat.of().formatHex(sign(secret, payload));
    }
}

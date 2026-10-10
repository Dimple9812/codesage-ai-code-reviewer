package com.codesage.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codesage.config.AppProperties;

/**
 * AES-256-GCM encryption for secrets at rest (GitHub access tokens, user supplied LLM keys).
 * The key comes from TOKEN_ENCRYPTION_KEY. If it is missing an ephemeral key is generated, which
 * means stored tokens become unreadable after a restart (users simply sign in again) - a warning is logged.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(AppProperties properties) {
        this.key = new SecretKeySpec(deriveKey(properties.tokenEncryptionKey()), "AES");
    }

    private byte[] deriveKey(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("TOKEN_ENCRYPTION_KEY is not set - using an ephemeral key. Stored GitHub tokens will not survive a restart.");
            byte[] k = new byte[32];
            random.nextBytes(k);
            return k;
        }
        try {
            // Accept a base64 32-byte key directly; otherwise derive 32 bytes via SHA-256.
            byte[] decoded = tryBase64(configured);
            if (decoded != null && decoded.length == 32) {
                return decoded;
            }
            return MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialise token encryption", e);
        }
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Returns null when the value cannot be decrypted (e.g. the key changed). */
    public String decrypt(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] in = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
            if (in.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Unable to decrypt a stored secret (encryption key changed?)");
            return null;
        }
    }
}

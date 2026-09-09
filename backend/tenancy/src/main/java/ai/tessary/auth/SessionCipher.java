// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static ai.tessary.crypto.CryptoConstants.ALG;
import static ai.tessary.crypto.CryptoConstants.IV_LEN;
import static ai.tessary.crypto.CryptoConstants.TAG_BITS;
import static ai.tessary.crypto.CryptoConstants.XFORM;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM sealing for the session cookie. Mirrors {@link ai.tessary.crypto.SecretBox}
 * but with its own key ({@code TESSARY_AUTH_COOKIE_PASSWORD}) so a leaked ingestion-credential
 * key never compromises sessions and vice versa.
 *
 * <p>Output format: {@code base64url( IV(12B) || ciphertext+tag(16B) )}. Base64-url so
 * the value is safe in a cookie without further escaping.</p>
 */
@Component
public final class SessionCipher {

    private final byte @Nullable [] key;
    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper mapper;

    public SessionCipher(AuthProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        String b64 = props.getCookiePassword();
        if (b64 == null || b64.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TESSARY_AUTH_COOKIE_PASSWORD must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "TESSARY_AUTH_COOKIE_PASSWORD must decode to 32 bytes (AES-256); got " + raw.length);
        }
        this.key = raw;
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** Seal a sealed-session record into a cookie-safe string. */
    public String seal(SealedSession session) {
        byte[] k = requireKey();
        try {
            byte[] plaintext = mapper.writeValueAsBytes(session);
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, ALG), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(IV_LEN + ct.length);
            buf.put(iv).put(ct);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("SessionCipher.seal failed", e);
        }
    }

    /** Unseal a cookie value. Returns null on any failure (tampered, wrong key, expired-tag). */
    public @Nullable SealedSession unseal(@Nullable String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank() || key == null) return null;
        try {
            byte[] all = Base64.getUrlDecoder().decode(cookieValue);
            if (all.length <= IV_LEN) return null;
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALG), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return mapper.readValue(new String(pt, StandardCharsets.UTF_8), SealedSession.class);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "TESSARY_AUTH_COOKIE_PASSWORD is not configured; set it to base64 of 32 random bytes");
        }
        return key;
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.crypto;

import static ai.tessary.crypto.CryptoConstants.ALG;
import static ai.tessary.crypto.CryptoConstants.IV_LEN;
import static ai.tessary.crypto.CryptoConstants.TAG_BITS;
import static ai.tessary.crypto.CryptoConstants.XFORM;

import ai.tessary.config.TessaryProperties;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope for ingestion-source credentials.
 * Format on disk: {@code base64( IV(12B) || ciphertext+tag(16B) )}.
 * <p>Master key from {@code tessary.secret-key} (base64 of 32 raw bytes). If absent,
 * encryption methods throw — callers should refuse to create sources without it.
 * Decryption similarly throws on a missing or wrong key, on tampered ciphertext,
 * or on malformed input.
 */
@Component
public final class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

    private final byte @Nullable [] key;
    private final SecureRandom rng = new SecureRandom();

    public SecretBox(TessaryProperties props) {
        String b64 = props.getSecretKey();
        if (b64 == null || b64.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("tessary.secret-key is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("tessary.secret-key must decode to 32 bytes (AES-256); got " + raw.length);
        }
        this.key = raw;
        log.info("SecretBox initialised; key fingerprint={}", fingerprint());
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String fingerprint() {
        if (key == null) return "(unset)";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (Exception e) {
            return "(error)";
        }
    }

    public String seal(String plaintext) {
        byte[] k = requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, ALG), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("SecretBox.seal failed", e);
        }
    }

    public String open(String token) {
        byte[] k = requireKey();
        byte[] all;
        try {
            all = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SecretBox.open: invalid base64", e);
        }
        if (all.length <= IV_LEN) {
            throw new IllegalStateException("SecretBox.open: ciphertext too short");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, ALG), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("SecretBox.open failed (wrong key or tampered ciphertext)", e);
        }
    }

    private byte[] requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "tessary.secret-key is not configured; set TESSARY_SECRET_KEY to base64-encoded 32 bytes");
        }
        return key;
    }
}

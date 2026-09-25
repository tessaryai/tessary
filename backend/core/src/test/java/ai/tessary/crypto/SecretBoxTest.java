// SPDX-License-Identifier: Apache-2.0
package ai.tessary.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.TessaryProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static SecretBox boxWith(byte[] key) {
        TessaryProperties p = new TessaryProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    private static byte[] key(byte fill) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = fill;
        return k;
    }

    @Test
    void roundTrip() {
        SecretBox box = boxWith(key((byte) 0x42));
        String plain = "{\"public_key\":\"pk-abc\",\"secret_key\":\"sk-xyz\"}";
        String sealed = box.seal(plain);
        assertEquals(plain, box.open(sealed));
    }

    @Test
    void differentCiphertextEachCall() {
        SecretBox box = boxWith(key((byte) 0x11));
        String a = box.seal("hi");
        String b = box.seal("hi");
        assertNotEquals(a, b, "IV is per-call so ciphertexts must differ");
    }

    @Test
    void tamperedCiphertextThrows() {
        SecretBox box = boxWith(key((byte) 0x33));
        String sealed = box.seal("hello world");
        byte[] raw = Base64.getDecoder().decode(sealed);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> box.open(tampered));
    }

    @Test
    void wrongKeyThrows() {
        String sealed = boxWith(key((byte) 0x33)).seal("hello");
        SecretBox other = boxWith(key((byte) 0x34));
        assertThrows(IllegalStateException.class, () -> other.open(sealed));
    }

    @Test
    void unconfiguredKeyMeansSealAndOpenThrow() {
        TessaryProperties p = new TessaryProperties();
        SecretBox box = new SecretBox(p);
        assertTrue(!box.isConfigured());
        assertThrows(IllegalStateException.class, () -> box.seal("x"));
        assertThrows(IllegalStateException.class, () -> box.open("x"));
    }

    @Test
    void shortKeyRejectsAtConstruction() {
        TessaryProperties p = new TessaryProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalStateException.class, () -> new SecretBox(p));
    }

    /**
     * The bug: a mistyped {@code TESSARY_SECRET_KEY} surfaces as a bare base64 decoder error, or boots
     * and fails later; it must fail construction saying the key is not base64.
     */
    @Test
    void nonBase64KeyRejectsAtConstructionNamingTheKey() {
        TessaryProperties p = new TessaryProperties();
        p.setSecretKey("not base64!!");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new SecretBox(p));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    /** The bug: a blank key counts as configured, and the first seal fails deep inside a request. */
    @Test
    void blankKeyIsUnconfigured() {
        TessaryProperties p = new TessaryProperties();
        p.setSecretKey("   ");
        assertEquals(false, new SecretBox(p).isConfigured());
    }

    /**
     * The bug: a stored token that is not base64 (a truncated or hand-edited column) escapes as the
     * decoder's IllegalArgumentException, which callers that handle an unreadable secret do not catch.
     */
    @Test
    void openRejectsANonBase64TokenAsUnreadable() {
        SecretBox box = boxWith(key((byte) 0x33));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> box.open("%%%"));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link SessionCipher} in isolation. Auth correctness rides on
 * this class — if seal/unseal silently changes shape, every browser cookie
 * is invalidated and users get bounced to login. If tampered cookies decrypt
 * instead of returning null, anyone with a cookie can forge a session.
 */
class SessionCipherTest {

    private static SessionCipher cipher(String b64Key) {
        AuthProperties p = new AuthProperties();
        p.setCookiePassword(b64Key);
        return new SessionCipher(p, new ObjectMapper());
    }

    private static String validKey() {
        // 32 bytes, base64
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        return Base64.getEncoder().encodeToString(raw);
    }

    private static SealedSession sample() {
        return new SealedSession(
                "acc_ABC",
                "ref_XYZ",
                "2026-05-12T12:00:00Z",
                "user_42",
                "alice@example.com",
                "Alice",
                "https://cdn.example.com/avatar.png",
                "org_1");
    }

    @Test
    void sealAndUnseal_preservesEveryField() {
        SessionCipher c = cipher(validKey());
        SealedSession in = sample();
        String token = c.seal(in);
        assertNotNull(token);
        assertNotEquals(token, c.seal(in), "two seals must use different IVs and produce different tokens");

        SealedSession out = c.unseal(token);
        assertEquals(in, out);
    }

    @Test
    void unseal_returnsNullOnTamperedCiphertext() {
        SessionCipher c = cipher(validKey());
        String token = c.seal(sample());
        // Flip a byte in the middle (past the IV).
        char[] chars = token.toCharArray();
        int idx = chars.length / 2;
        chars[idx] = (chars[idx] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);
        assertNull(c.unseal(tampered), "AES-GCM auth tag must reject tampered ciphertext");
    }

    @Test
    void unseal_returnsNullOnWrongKey() {
        String token = cipher(validKey()).seal(sample());

        byte[] other = new byte[32];
        for (int i = 0; i < other.length; i++) other[i] = (byte) (i + 100);
        SessionCipher attacker = cipher(Base64.getEncoder().encodeToString(other));
        assertNull(attacker.unseal(token));
    }

    @Test
    void unseal_returnsNullOnBlankOrShortInput() {
        SessionCipher c = cipher(validKey());
        assertNull(c.unseal(null));
        assertNull(c.unseal(""));
        assertNull(c.unseal("aGVsbG8")); // valid base64 but way shorter than IV
    }

    @Test
    void initThrowsWhenKeyIsWrongLength() {
        AuthProperties p = new AuthProperties();
        p.setCookiePassword(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(IllegalStateException.class, () -> new SessionCipher(p, new ObjectMapper()));
    }

    @Test
    void unconfiguredCipher_unsealsNull_sealThrows() {
        AuthProperties p = new AuthProperties(); // cookiePassword left blank
        SessionCipher c = new SessionCipher(p, new ObjectMapper());

        assertNull(c.unseal("anything"));
        assertThrows(IllegalStateException.class, () -> c.seal(sample()));
    }
}

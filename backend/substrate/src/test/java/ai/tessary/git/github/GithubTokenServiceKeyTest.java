// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Private-key parsing accepts both the PKCS#1 form GitHub Apps download
 * ({@code BEGIN RSA PRIVATE KEY}) and PKCS#8 ({@code BEGIN PRIVATE KEY}).
 * Both fixtures are the SAME key in the two formats, so parsing either must yield byte-identical key material.
 * The key is generated per run, so no private key is committed for secret scanners to flag.
 */
class GithubTokenServiceKeyTest {

    /**
     * A 2048-bit RSA PrivateKeyInfo opens with a fixed 26-byte header (SEQUENCE, version, rsaEncryption
     * AlgorithmIdentifier, OCTET STRING); what follows is the PKCS#1 RSAPrivateKey.
     */
    private static final int PKCS8_RSA_2048_HEADER = 26;

    private static final byte[] PKCS8_DER = generate();
    private static final String PKCS8 = pem("PRIVATE KEY", PKCS8_DER);
    private static final String PKCS1 =
            pem("RSA PRIVATE KEY", Arrays.copyOfRange(PKCS8_DER, PKCS8_RSA_2048_HEADER, PKCS8_DER.length));

    private static byte[] generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair().getPrivate().getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    @Test
    void parsesPkcs1GitHubKey() throws Exception {
        PrivateKey key = GithubTokenService.parsePrivateKey(PKCS1);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    void pkcs1AndPkcs8YieldSameKey() throws Exception {
        // Same key, two encodings — the PKCS#1 wrapping must reconstruct the
        // exact PKCS#8 PrivateKeyInfo the JDK produces for the PKCS#8 input.
        PrivateKey fromPkcs1 = GithubTokenService.parsePrivateKey(PKCS1);
        PrivateKey fromPkcs8 = GithubTokenService.parsePrivateKey(PKCS8);
        assertArrayEquals(fromPkcs8.getEncoded(), fromPkcs1.getEncoded());
        assertArrayEquals(PKCS8_DER, fromPkcs1.getEncoded());
    }
}

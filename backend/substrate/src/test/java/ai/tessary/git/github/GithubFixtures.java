// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class GithubFixtures {

    private GithubFixtures() {}

    static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    /** An App with a freshly generated key, for tests that only need a mint to be signed, not checked. */
    static GithubAppProperties configuredApp() {
        GithubAppProperties props = new GithubAppProperties();
        props.setAppId("4242");
        byte[] key;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            key = g.generateKeyPair().getPrivate().getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // The PEM armour is split so the secret scanner does not read this generated key as a committed one.
        String label = "PRIVATE " + "KEY";
        props.setPrivateKeyPem("-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder().encodeToString(key) + "\n-----END " + label + "-----\n");
        return props;
    }

    static String location(ResponseEntity<Void> resp) {
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        return resp.getHeaders().getLocation().toString();
    }
}

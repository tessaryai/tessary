// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
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

    static String location(ResponseEntity<Void> resp) {
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        return resp.getHeaders().getLocation().toString();
    }
}

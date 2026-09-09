// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The PAT fallback fixed in #860: {@link GithubTokenService#authHeader} must try a sealed
 * personal-access-token BEFORE gating on {@code GithubAppProperties.isConfigured()} — that gate
 * used to be the unconditional first line, rejecting every PAT-mode integration even though PAT
 * mode exists precisely for the no-App case.
 */
class GithubTokenServicePatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 3);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    private GitIntegrationRow patIntegration(SecretBox box, String token) throws Exception {
        String enc = box.seal(mapper.writeValueAsString(Map.of("token", token)));
        return new GitIntegrationRow("i1", "p1", "github", null, "acme", "web", "main", enc, null, "t", "t");
    }

    @Test
    void patTokenIsUsedEvenWhenAppIsNotConfigured() throws Exception {
        SecretBox box = secretBox();
        // Unconfigured GithubAppProperties — the exact case the old unconditional isConfigured()
        // gate rejected before #860.
        GithubAppProperties props = new GithubAppProperties();
        GithubTokenService svc = new GithubTokenService(props, box, mapper);

        GitIntegrationRow integ = patIntegration(box, "ghp_abc123");
        assertEquals("Bearer ghp_abc123", svc.authHeader(integ));
    }

    @Test
    void patTokenIsNotCached_rotatedTokenTakesEffectImmediately() throws Exception {
        SecretBox box = secretBox();
        GithubAppProperties props = new GithubAppProperties();
        GithubTokenService svc = new GithubTokenService(props, box, mapper);

        GitIntegrationRow first = patIntegration(box, "ghp_old");
        assertEquals("Bearer ghp_old", svc.authHeader(first));

        // Same integration id, different sealed token — simulates a self-hoster rotating the PAT.
        // A cached-forever PAT would keep serving "ghp_old" here; it must not.
        GitIntegrationRow rotated = patIntegration(box, "ghp_new");
        assertEquals("Bearer ghp_new", svc.authHeader(rotated));
    }

    @Test
    void noPatFallsThroughToAppConfiguredGate() {
        GithubAppProperties props = new GithubAppProperties(); // unconfigured
        GithubTokenService svc = new GithubTokenService(props, secretBox(), mapper);
        // No credentialsEnc at all (e.g. App-installation-mode row bound via installationId only,
        // sealed elsewhere) — installationId-only rows are covered by other tests; here we assert
        // the no-credentials case still reaches the App gate rather than silently no-op'ing.
        GitIntegrationRow integ =
                new GitIntegrationRow("i2", "p1", "github", null, "acme", "web", "main", null, null, "t", "t");
        TessaryException e = assertThrows(TessaryException.class, () -> svc.authHeader(integ));
        assertEquals(GitError.MISSING_APP_CONFIG, e.error());
    }
}

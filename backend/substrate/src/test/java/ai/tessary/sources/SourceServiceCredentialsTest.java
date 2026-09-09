// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SourceService#openCredentials} — the live-fetch credential open invoked on
 * the upstream pull path. Regression: a source pull over a non-network provider
 * ({@code fake}) is persisted with an empty seal ({@code credentialsEnc = ""}), so the old
 * unconditional {@code secretBox.open("")} threw {@code "ciphertext too short"} and the pull failed
 * before fetching. Non-network providers must short-circuit to an empty credentials map, while real
 * providers still decrypt their sealed credentials.
 *
 * <p>Collaborators the tested method never touches are passed {@code null} (the project avoids
 * Mockito; NullAway is suppressed for those deliberate test nulls).
 */
@SuppressWarnings("NullAway")
class SourceServiceCredentialsTest {

    /** Base64 of 32 zero bytes — a valid AES-256 key for the SecretBox. */
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static SourceService service(SecretBox secretBox) {
        return new SourceService(null, secretBox, new ObjectMapper());
    }

    private static SecretBox configuredBox() {
        TessaryProperties props = new TessaryProperties();
        props.setSecretKey(KEY);
        return new SecretBox(props);
    }

    private static SourceRow row(String provider, String credentialsEnc) {
        return new SourceRow("src-1", "proj-1", provider, "s", "fake://upstream", credentialsEnc, "t", "t");
    }

    @Test
    void fakeSourceOpensToEmptyMap_withoutDecryptingTheEmptySeal() {
        // The fake source is created with an empty seal (see SourceService.create / NON_NETWORK_PROVIDERS);
        // opening it must NOT call secretBox.open("") — which would throw "ciphertext too short".
        Map<String, String> creds = service(configuredBox()).openCredentials(row(SourceService.FAKE_PROVIDER, ""));

        assertTrue(creds.isEmpty(), "non-network source opens to an empty credentials map");
    }

    @Test
    void fakeSourceOpensEvenWithNoSecretKeyConfigured() {
        // A fake source carries no secret, so it must be gradable regardless of secret-key config —
        // the short-circuit runs before the isConfigured() guard.
        SecretBox unconfigured = new SecretBox(new TessaryProperties());
        assertTrue(!unconfigured.isConfigured(), "precondition: no secret key");

        Map<String, String> creds = service(unconfigured).openCredentials(row(SourceService.FAKE_PROVIDER, ""));

        assertTrue(creds.isEmpty(), "non-network source opens with no secret key configured");
    }

    @Test
    void realSourceStillDecryptsItsSealedCredentials() {
        SecretBox box = configuredBox();
        String sealed = box.seal("{\"apiKey\":\"sk-123\"}");

        Map<String, String> creds = service(box).openCredentials(row("legacy-vendor", sealed));

        assertEquals(Map.of("apiKey", "sk-123"), creds, "network source decrypts its real credentials");
    }
}

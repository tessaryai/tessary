// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The Secret Leak detector's three readings (the redaction stamp, the raw output, a bare redaction token) and
 * the band each earns. The credentials below are FAKE, structurally valid shapes exempted from this repo's
 * secret scan in {@code .gitleaks.toml}.
 */
class SecretLeakDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretLeakDetector detector = new SecretLeakDetector(MAPPER);

    private Detection detect(@Nullable String output) {
        return detect(output, null);
    }

    private Detection detect(@Nullable String output, @Nullable String redactions) {
        SubstrateObservation obs = new SubstrateObservation(
                "obs-1",
                "p",
                "t",
                "s",
                null,
                null,
                "llm",
                "chat",
                null,
                output,
                null,
                "2026-01-01T00:00:00Z",
                redactions);
        return detector.detect(obs, null);
    }

    // ---- the stamp -------------------------------------------------------------------------------------------

    @Test
    void anUnanchoredStampIsLow() {
        Detection d = detect(
                "api_key = [REDACTED_SECRET]",
                "[{\"rule\":\"generic-api-key\",\"field\":\"output\",\"anchored\":false}]");
        assertEquals(Detection.Confidence.LOW, d.confidence(), "a vendor name beside a random string");
        assertEquals("generic-api-key", evidence(d).path("pattern").asText());
    }

    @Test
    void anAnchoredRuleWhoseShapeIsNotReliablyALeakIsLow() {
        for (String rule : SecretLeakDetector.LOW_BAND_RULES) {
            Detection d =
                    detect("[REDACTED_SECRET]", "[{\"rule\":\"" + rule + "\",\"field\":\"output\",\"anchored\":true}]");
            assertEquals(Detection.Confidence.LOW, d.confidence(), rule);
        }
    }

    @Test
    void theStrongestStampOnTheOutputWinsWhateverItsOrder() {
        Detection d = detect(
                "[REDACTED_SECRET] and [REDACTED_SECRET]",
                "[{\"rule\":\"generic-api-key\",\"field\":\"output\",\"anchored\":false},"
                        + "{\"rule\":\"github-pat\",\"field\":\"output\",\"anchored\":true}]");
        assertEquals(Detection.Confidence.HIGH, d.confidence());
        assertEquals("github-pat", evidence(d).path("pattern").asText());
    }

    @Test
    void aStampOnTheInputIsNotALeak() {
        // A user pasting a credential is input hygiene, which this classifier does not claim.
        assertFalse(detect("ok", "[{\"rule\":\"aws-access-token\",\"field\":\"input\",\"anchored\":true}]")
                .fired());
    }

    // ---- the raw output --------------------------------------------------------------------------------------

    @Test
    void anUnredactedProviderKeyInTheOutputIsHigh() {
        Detection d = detect("deploy with AKIA" + "QYLPMN5HHHFPZAM2 for staging");
        assertEquals(Detection.Confidence.HIGH, d.confidence());
        JsonNode evidence = evidence(d);
        assertEquals("aws-access-token", evidence.path("pattern").asText());
        assertEquals("output", evidence.path("source").asText());
        assertEquals("raw", evidence.path("stored").asText());
        assertEquals("AKIA…ZAM2", evidence.path("masked").asText(), "provider prefix and the last 4 characters");
    }

    @Test
    void evidenceNeverEchoesTheCredential() {
        String secret = "ghp_aB3dE5gH7jK9mN1pQ3sT5vX7zA9cE1gI3kM5";
        Detection d = detect("token: " + secret);
        assertTrue(d.fired());
        assertFalse(Objects.requireNonNull(d.evidenceJson()).contains(secret));
    }

    @Test
    void aPlaceholderOrProseIsNotALeak() {
        assertFalse(detect("set api_key = ${API_KEY} before you run it").fired());
        assertFalse(detect("A password is required to continue.").fired());
        assertFalse(
                detect("The deployment finished successfully in 42 seconds.").fired());
        assertFalse(detect("").fired());
        assertFalse(detect(null).fired());
    }

    // ---- a bare token ----------------------------------------------------------------------------------------

    @Test
    void aRedactionTokenWithNoStampIsLow() {
        // Stored before stamping existed, taken by a prefix rule the corpus does not share, or redacted upstream:
        // something was removed, and nothing says what.
        for (String token : new String[] {
            "[REDACTED_API_KEY]",
            "[REDACTED_CREDENTIAL]",
            "[REDACTED_JWT]",
            "[REDACTED_PRIVATE_KEY]",
            "[REDACTED_SECRET]"
        }) {
            Detection d = detect("the value was " + token);
            assertTrue(d.fired(), token);
            assertEquals(Detection.Confidence.LOW, d.confidence(), token);
            JsonNode evidence = evidence(d);
            assertEquals("marker", evidence.path("source").asText(), token);
            assertEquals("redacted", evidence.path("stored").asText(), token);
        }
    }

    @Test
    void anUnreadableStampFallsThroughToTheOutput() {
        Detection d = detect("key AKIA" + "QYLPMN5HHHFPZAM2", "not json");
        assertEquals(Detection.Confidence.HIGH, d.confidence(), "a broken stamp must not hide a leak in plain sight");
    }

    /**
     * Read from the raw output, a lone keyword-context match is LOW, and a provider key later in the same
     * output still wins: taking the first match would let a weak one mask the real leak beside it.
     */
    @Test
    void theStrongestMatchInTheOutputWinsAndALoneWeakOneIsLow() {
        Detection weak = detect("api_key = \"q7Zr2mK9xW4vN8pLr5Tq\"");
        assertEquals(Detection.Confidence.LOW, weak.confidence());
        assertEquals("generic-api-key", evidence(weak).path("pattern").asText());

        Detection both = detect("api_key = \"q7Zr2mK9xW4vN8pLr5Tq\" and AKIA" + "QYLPMN5HHHFPZAM2");
        assertEquals(Detection.Confidence.HIGH, both.confidence());
        assertEquals("aws-access-token", evidence(both).path("pattern").asText());
    }

    private static JsonNode evidence(Detection d) {
        try {
            return MAPPER.readTree(Objects.requireNonNull(d.evidenceJson()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import ai.tessary.evals.testsupport.ClassifierObservations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SecretLeakDetectorTest {

    private final SecretLeakDetector detector = new SecretLeakDetector(new ObjectMapper());

    private Detection detect(@Nullable String output) {
        SubstrateObservation obs = new SubstrateObservation(
                "obs-1", "p", "t", "s", null, null, "llm", "chat", null, output, null, "2026-01-01T00:00:00Z");
        return detector.detect(obs, null);
    }

    @Test
    void providerShapedKeysFireHighAndCritical() {
        Detection aws = detect("Sure — your key is AKIAIOSFODNN7EXAMPLE and the region is us-east-1.");
        assertTrue(aws.fired(), "AWS access key id fires");
        assertEquals(Detection.Severity.CRITICAL, aws.severity());
        assertEquals(Detection.Confidence.HIGH, aws.confidence());
        String evidence = Objects.requireNonNull(aws.evidenceJson());
        assertTrue(evidence.contains("aws-access-key-id"), "evidence names the pattern");

        assertTrue(detect("token: ghp_abcdefghijklmnopqrstuvwxyz0123456789").fired(), "GitHub classic token fires");
        assertTrue(detect("Use xoxb-1234567890-abcdefghij for the bot.").fired(), "Slack token fires");
        assertTrue(detect("-----BEGIN RSA PRIVATE KEY-----\nMIIEow...").fired(), "PEM block fires");
        assertTrue(
                detect("set ANTHROPIC_API_KEY=sk-ant-api03-abcdefghijklmnopqrst")
                        .fired(),
                "Anthropic key fires");
        assertTrue(detect("maps key AIzaSyA1234567890abcdefghijklmnopqrstuv").fired(), "Google API key fires");
        assertTrue(detect("stripe: sk_live_abcdefghijklmnop").fired(), "Stripe live key fires");
        assertTrue(
                detect("Authorization: Bearer dGhpcy1pcy1hLXZlcnktbG9uZy1vcGFxdWUtdG9rZW4")
                        .fired(),
                "bearer token fires");
    }

    @Test
    void evidenceNeverEchoesTheSecret() {
        String secret = "AKIAIOSFODNN7EXAMPLE";
        Detection d = detect("here you go: " + secret);
        assertTrue(d.fired());
        String evidence = Objects.requireNonNull(d.evidenceJson());
        assertFalse(evidence.contains(secret), "the raw credential must not appear in evidence");
        assertTrue(evidence.contains("AKIA…"), "evidence keeps a 4-char redacted prefix");
    }

    @Test
    void pgpPrivateKeyBlockEvidenceRedactsTheHeaderNotTheCaptureGroup() {
        Detection d = detect("-----BEGIN PGP PRIVATE KEY BLOCK-----\nlQOYBGRr...");
        assertTrue(d.fired(), "PGP private-key armor header fires");
        String evidence = Objects.requireNonNull(d.evidenceJson());
        assertTrue(evidence.contains("private-key-block"), "evidence names the pattern");
        assertTrue(
                evidence.contains("----…"),
                "the redacted snippet is a prefix of the full header, not the optional-suffix capture group");
        assertFalse(evidence.contains(" BLO"), "the ' BLOCK' suffix group must not leak into evidence");
    }

    @Test
    void assignedSecretIsEntropyGatedAndLowConfidence() {
        Detection highEntropy = detect("config: api_key = \"q7Zr2mK9xW4vN8pL3sT6yB1cF5hJ0dGa\"");
        assertTrue(highEntropy.fired(), "high-entropy assigned value fires");
        assertEquals(Detection.Confidence.LOW, highEntropy.confidence(), "generic assignment is LOW band");

        assertFalse(detect("password = \"aaaaaaaaaaaaaaaaaaaa\"").fired(), "low-entropy value is gated out");
        assertFalse(detect("A password is required to continue.").fired(), "prose never fires");
    }

    @Test
    void firesOnAKeyInsideTheRealGenAiOutputEnvelope() {
        // SecretLeak scans the raw output column, so a credential in the assistant message of the
        // stored gen_ai envelope is still caught — the shape ingest actually writes.
        Detection d = detect(ClassifierObservations.assistantOutput(
                "Sure, the AWS key is AKIAIOSFODNN7EXAMPLE for the staging bucket."));
        assertTrue(d.fired(), "a key in the assistant envelope message fires");
        assertEquals(Detection.Severity.CRITICAL, d.severity());
        assertFalse(
                Objects.requireNonNull(d.evidenceJson()).contains("AKIAIOSFODNN7EXAMPLE"), "evidence stays redacted");
    }

    @Test
    void cleanAndEmptyOutputsAreQuiet() {
        assertFalse(
                detect("The deployment finished successfully in 42 seconds.").fired());
        assertFalse(detect("").fired());
        assertFalse(detect(null).fired());
    }

    @Test
    void entropyIsBitsPerCharacter() {
        assertEquals(0.0, SecretLeakDetector.shannonEntropy("aaaa"), 1e-9);
        assertTrue(SecretLeakDetector.shannonEntropy("q7Zr2mK9xW4vN8pL") > 3.5, "random-ish keys clear the floor");
        assertTrue(SecretLeakDetector.shannonEntropy("passwordpassword") < 3.5, "repetitive text stays below");
    }

    @Test
    void redactionMarkersFireAtTheirRuleBand() {
        // The write-path redaction rules run before the sweep, so on a default project the raw literal
        // is already `[REDACTED_API_KEY]` by the time this detector reads the persisted output (#1044).
        Detection apiKey = detect("Sure — your key is [REDACTED_API_KEY] and the region is us-east-1.");
        assertTrue(apiKey.fired(), "an API-key redaction token fires");
        assertEquals(Detection.Confidence.HIGH, apiKey.confidence());
        assertEquals(Detection.Severity.CRITICAL, apiKey.severity());
        assertTrue(
                Objects.requireNonNull(apiKey.evidenceJson()).contains("redacted-api-key"),
                "evidence names the token's rule");
        assertTrue(detect("Authorization: [REDACTED_CREDENTIAL]").fired(), "an authorization token fires");
        assertTrue(detect("session [REDACTED_JWT]").fired(), "a JWT token fires");
        assertTrue(detect("[REDACTED_PRIVATE_KEY]\nMIIEow...").fired(), "a private-key token fires");
        Detection secret = detect("password=[REDACTED_SECRET]");
        assertTrue(secret.fired(), "a secret-assignment token fires");
        assertEquals(Detection.Confidence.LOW, secret.confidence(), "at the generic assignment's LOW band");
        assertFalse(
                detect("mail me at [REDACTED_EMAIL], card [REDACTED_CARD]").fired(), "PII tokens are not credentials");
    }
}

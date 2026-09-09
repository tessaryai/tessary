// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Secret Leak built-in: a curated credential-pattern set matched over the observation's
 * OUTPUT (an agent echoing a credential is the leak; the user pasting one is an input-hygiene
 * problem this signal does not claim). No model call, no configuration — the pattern set is the
 * product's opinion of what a leaked credential looks like, in the spirit of gitleaks:
 *
 * <ul>
 *   <li>provider-shaped keys (AWS, GitHub, Slack, Google, Stripe, Anthropic/OpenAI);</li>
 *   <li>PEM private-key blocks and JWTs;</li>
 *   <li>a generic {@code key/secret/token/password = "value"} assignment, gated by a Shannon-entropy
 *       floor on the value so prose like {@code password is required} never fires.</li>
 * </ul>
 *
 * <p>Every pattern fires at {@link Detection.Confidence#HIGH} (these shapes are unambiguous) except
 * the entropy-gated generic assignment, which fires LOW — the discovery-vs-tracking split. Evidence
 * carries the pattern name and a REDACTED snippet (first four characters + length), never the match
 * itself: a leak detector must not re-emit the secret into a verdict row.
 *
 * <p><b>Redaction runs first, and the detector reads what it left.</b> The persisted output is what
 * this detector sees, and on a default project almost every credential shape above (all but the
 * Stripe key and the rarer AWS prefixes) is also a built-in redaction rule applied on the write path, so the raw literal is gone by the time the sweep runs;
 * with the raw patterns alone the classifier was dead on the OTLP path. The redaction rules
 * substitute a token that names what was removed ({@code [REDACTED_API_KEY]} and its siblings, see
 * {@code BuiltInRedactionRules}), and that token is the record of the leak: the detector fires on it
 * at the confidence the corresponding raw shape carries, with the token itself as the evidence
 * (never the credential). The secret-assignment token is broader than the entropy-gated raw pattern,
 * since that redaction rule has no entropy gate and a six-character floor, so {@code password:
 * required} redacts and then fires LOW; that is the discovery band doing what it is for. A token
 * that arrived already in the producer's own output (an upstream redaction, a re-ingested export)
 * fires the same way, by design: the marker says a credential was there. Nothing is persisted that
 * was not persisted before; the signal is recovered from the marker rather than from the secret.
 *
 * <p>This detector deliberately ignores {@code config_json} — the curated pattern set is the
 * product's opinion of what a credential looks like, not user-configurable state — a documented
 * carve-out from the {@code config_json}-override convention {@link RegexDetector} and
 * {@link EncoderDetector} follow.
 */
public final class SecretLeakDetector implements BuiltInDetector {

    /** A named credential pattern; {@code strong} decides the confidence band. */
    private record CredentialPattern(String name, Pattern pattern, boolean strong) {}

    /**
     * The tokens the write-path redaction rules leave in place of a credential, each paired with the
     * band its raw shape fires at: the API-key, authorization, JWT and private-key tokens replace
     * unambiguous shapes (HIGH); the secret-assignment token replaces the generic assignment, which
     * on the redaction side is ungated and so fires LOW. {@code [REDACTED_EMAIL]} and the other PII tokens are not credentials and are
     * not here.
     */
    private static final List<CredentialPattern> REDACTION_MARKERS = List.of(
            new CredentialPattern("redacted-api-key", Pattern.compile("\\[REDACTED_API_KEY\\]"), true),
            new CredentialPattern("redacted-credential", Pattern.compile("\\[REDACTED_CREDENTIAL\\]"), true),
            new CredentialPattern("redacted-jwt", Pattern.compile("\\[REDACTED_JWT\\]"), true),
            new CredentialPattern("redacted-private-key", Pattern.compile("\\[REDACTED_PRIVATE_KEY\\]"), true),
            new CredentialPattern("redacted-secret", Pattern.compile("\\[REDACTED_SECRET\\]"), false));

    private static final List<CredentialPattern> PATTERNS = List.of(
            new CredentialPattern(
                    "aws-access-key-id", Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"), true),
            new CredentialPattern(
                    "github-token", Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\\b"), true),
            new CredentialPattern(
                    "github-fine-grained-pat", Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,255}\\b"), true),
            new CredentialPattern("slack-token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"), true),
            new CredentialPattern(
                    "private-key-block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY(?: BLOCK)?-----"), true),
            new CredentialPattern(
                    "jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"), true),
            new CredentialPattern("anthropic-api-key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b"), true),
            new CredentialPattern(
                    "openai-api-key",
                    Pattern.compile("\\bsk-(?:proj-|svcacct-)?[A-Za-z0-9]{20,}T3BlbkFJ[A-Za-z0-9]{20,}\\b"),
                    true),
            new CredentialPattern("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), true),
            new CredentialPattern("stripe-key", Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}\\b"), true),
            new CredentialPattern("bearer-token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{30,}"), true),
            // Generic assigned secret: key/secret/token/password = "<value>". Entropy-gated (see
            // detect) and LOW-band: it widens recall in discovery mode without polluting tracking.
            new CredentialPattern(
                    "assigned-secret",
                    Pattern.compile("(?i)\\b(?:api[_-]?key|secret|token|password|passwd)\\b\\s*[:=]\\s*[\"']?"
                            + "([A-Za-z0-9+/_=-]{16,})[\"']?"),
                    false));

    /** Shannon-entropy floor (bits/char) for the generic assigned-secret value — prose sits well below. */
    private static final double MIN_ENTROPY_BITS = 3.5;

    private final ObjectMapper mapper;

    public SecretLeakDetector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return Kind.SECRET_LEAK;
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        // Deliberately the RAW output, not the flattened text view: a leaked credential is a leak
        // wherever it appears in the output payload — including tool-call arguments and structured
        // parts a text flatten drops. Credential patterns are alnum, unaffected by JSON escaping.
        String haystack = obs.output();
        if (haystack == null || haystack.isBlank()) return Detection.none();
        for (CredentialPattern cp : PATTERNS) {
            String matched = firstCredentialMatch(cp, haystack);
            if (matched != null) {
                String confidence = cp.strong() ? Detection.Confidence.HIGH : Detection.Confidence.LOW;
                return Detection.fired(Detection.Severity.CRITICAL, evidence(cp.name(), matched), confidence);
            }
        }
        // A marker is a constant, so the entropy gate does not apply; the band comes from the rule.
        for (CredentialPattern cp : REDACTION_MARKERS) {
            Matcher m = cp.pattern().matcher(haystack);
            if (m.find()) {
                String confidence = cp.strong() ? Detection.Confidence.HIGH : Detection.Confidence.LOW;
                return Detection.fired(Detection.Severity.CRITICAL, evidenceJson(cp.name(), m.group()), confidence);
            }
        }
        return Detection.none();
    }

    /** The first match of {@code cp} in {@code haystack} that survives the entropy gate, or null. */
    private static @Nullable String firstCredentialMatch(CredentialPattern cp, String haystack) {
        Matcher m = cp.pattern().matcher(haystack);
        while (m.find()) {
            String matched = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            // A strong pattern is a credential by shape; a weak one must clear the entropy floor
            // (a low-entropy value is prose or a placeholder, so the scan continues past it).
            if (cp.strong() || shannonEntropy(matched) >= MIN_ENTROPY_BITS) {
                return matched;
            }
        }
        return null;
    }

    /** Redacted evidence: the pattern name plus first-4-chars + length, never the credential itself. */
    private String evidence(String patternName, String match) {
        return evidenceJson(
                patternName, match.substring(0, Math.min(4, match.length())) + "…(" + match.length() + " chars)");
    }

    private String evidenceJson(String patternName, String matchRedacted) {
        try {
            return mapper.writeValueAsString(Map.of("pattern", patternName, "match_redacted", matchRedacted));
        } catch (JsonProcessingException e) {
            return "{\"pattern\":\"" + patternName + "\"}";
        }
    }

    /** Shannon entropy in bits per character. */
    static double shannonEntropy(String s) {
        if (s.isEmpty()) return 0;
        int[] counts = new int[128];
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) counts[c]++;
            else other++;
        }
        double entropy = 0;
        for (int count : counts) {
            if (count == 0) continue;
            double p = (double) count / s.length();
            entropy -= p * Math.log(p) / Math.log(2);
        }
        if (other > 0) {
            double p = (double) other / s.length();
            entropy -= p * Math.log(p) / Math.log(2);
        }
        return entropy;
    }
}

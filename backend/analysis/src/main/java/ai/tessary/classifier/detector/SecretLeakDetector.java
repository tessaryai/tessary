// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.ingest.RedactionStamp;
import ai.tessary.redaction.CredentialMasking;
import ai.tessary.redaction.GitleaksCorpus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Secret Leak built-in: the agent's OUTPUT carried a credential. An agent echoing a credential is the
 * leak; a user pasting one is an input-hygiene problem this classifier does not claim.
 *
 * <p><b>What it reads, in order.</b>
 *
 * <ol>
 *   <li><b>The redaction stamp.</b> Redaction runs the gitleaks corpus on the write path and replaces a
 *       credential before anything is stored, recording which rule matched in {@code span_payload.redactions}.
 *       That is the record of the leak, and it names it: an {@code aws-access-token} rather than a token
 *       that says only that something was removed.
 *   <li><b>The output itself.</b> A project that turned redaction off stores credentials as they came, so the
 *       same corpus runs over the stored output. On a project that redacts, it finds nothing, because the
 *       credentials are gone.
 *   <li><b>A redaction token with no stamp.</b> A span stored before stamping existed, one a regex rule
 *       redacted (the corpus does not name every shape the prefix rules catch), or one a producer redacted
 *       upstream. Something was removed, and nothing says what.
 * </ol>
 *
 * <p><b>The band says how sure the match is that it is a credential.</b> HIGH is a rule anchored on a literal
 * the provider stamps into the credential ({@code AKIA}, {@code ghp_}, {@code xoxb-}): the shape is the
 * proof. LOW is everything else: a vendor's name near a random-looking string, the five anchored rules in
 * {@link #LOW_BAND_RULES} whose shape still turns up on text that is not a leak, and a bare redaction token.
 * Only HIGH counts toward the classifier's arming bar; LOW stays listed for anyone looking.
 *
 * <p>Evidence names the rule and never carries the credential. It also names, and masks, which key leaked:
 * reading the stamp, the mask travels with it when the stamp carries one; reading raw output, this
 * computes {@link CredentialMasking#maskedKey}. Either way what reaches evidence is a provider prefix and
 * four trailing characters, never the credential itself.
 *
 * <p>This detector deliberately ignores {@code config_json}: the corpus is the product's opinion of what a
 * credential looks like, not per-project state. A documented carve-out from the convention {@link
 * RegexDetector} follows.
 */
public final class SecretLeakDetector implements BuiltInDetector {

    /**
     * Anchored rules that still fire LOW. Their shape is precise, but what it describes is not reliably a
     * leak: a JWT in an output is as often a public id token or a documentation example as a live session;
     * an {@code Authorization} header inside a {@code curl} line is what every API reference prints; and a
     * Kubernetes {@code kind: Secret} manifest is how one is written down, usually with placeholder data.
     */
    static final Set<String> LOW_BAND_RULES =
            Set.of("jwt", "jwt-base64", "curl-auth-header", "curl-auth-user", "kubernetes-secret-yaml");

    /**
     * The tokens redaction leaves in place of a credential. All LOW: a token says something was removed and
     * cannot say what, which is exactly the ambiguity the stamp exists to resolve.
     */
    private static final Map<String, Pattern> REDACTION_MARKERS = markers();

    private static Map<String, Pattern> markers() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        m.put("redacted-api-key", Pattern.compile("\\[REDACTED_API_KEY]"));
        m.put("redacted-credential", Pattern.compile("\\[REDACTED_CREDENTIAL]"));
        m.put("redacted-jwt", Pattern.compile("\\[REDACTED_JWT]"));
        m.put("redacted-private-key", Pattern.compile("\\[REDACTED_PRIVATE_KEY]"));
        m.put("redacted-secret", Pattern.compile("\\[REDACTED_SECRET]"));
        return Map.copyOf(m);
    }

    /** Where evidence says the rule was read from. */
    static final class Source {
        private Source() {}

        static final String REDACTION = "redaction";
        static final String OUTPUT = "output";
        static final String MARKER = "marker";
    }

    private final ObjectMapper mapper;
    private final GitleaksCorpus corpus;

    public SecretLeakDetector(ObjectMapper mapper) {
        this(mapper, GitleaksCorpus.get());
    }

    SecretLeakDetector(ObjectMapper mapper, GitleaksCorpus corpus) {
        this.mapper = mapper;
        this.corpus = corpus;
    }

    @Override
    public String kind() {
        return Kind.SECRET_LEAK;
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        Detection stamped = fromStamps(obs.redactionsJson());
        if (stamped != null) return stamped;

        // Deliberately the RAW output, not the flattened text view: a leaked credential is a leak wherever
        // it appears in the output payload, tool-call arguments and structured parts a text flatten drops
        // included. Credential shapes are alphanumeric, so JSON escaping does not hide them.
        String haystack = obs.output();
        if (haystack == null || haystack.isBlank()) return Detection.none();

        List<GitleaksCorpus.Finding> findings = corpus.find(haystack);
        if (!findings.isEmpty()) {
            GitleaksCorpus.Finding best = strongest(findings);
            String match = haystack.substring(best.start(), best.end());
            return fired(
                    best.ruleId(),
                    band(best.ruleId(), best.anchored()),
                    Source.OUTPUT,
                    CredentialMasking.maskedKey(match));
        }

        for (Map.Entry<String, Pattern> marker : REDACTION_MARKERS.entrySet()) {
            Matcher m = marker.getValue().matcher(haystack);
            if (m.find()) return fired(marker.getKey(), Detection.Confidence.LOW, Source.MARKER, null);
        }
        return Detection.none();
    }

    /** A detection from what redaction recorded removing from the output, or null when it recorded nothing. */
    private @Nullable Detection fromStamps(@Nullable String redactionsJson) {
        if (redactionsJson == null || redactionsJson.isBlank()) return null;
        JsonNode stamps;
        try {
            stamps = mapper.readTree(redactionsJson);
        } catch (JsonProcessingException e) {
            return null;
        }
        String lowRule = null;
        String lowMasked = null;
        for (JsonNode stamp : stamps) {
            if (!RedactionStamp.OUTPUT.equals(stamp.path("field").asText())) continue;
            String rule = stamp.path("rule").asText(null);
            if (rule == null) continue;
            String masked = stamp.path("masked").asText(null);
            if (Detection.Confidence.HIGH.equals(
                    band(rule, stamp.path("anchored").asBoolean(false)))) {
                return fired(rule, Detection.Confidence.HIGH, Source.REDACTION, masked);
            }
            if (lowRule == null) {
                lowRule = rule;
                lowMasked = masked;
            }
        }
        return lowRule == null ? null : fired(lowRule, Detection.Confidence.LOW, Source.REDACTION, lowMasked);
    }

    /** The first HIGH finding, or the first finding when none is HIGH. */
    private static GitleaksCorpus.Finding strongest(List<GitleaksCorpus.Finding> findings) {
        for (GitleaksCorpus.Finding f : findings) {
            if (Detection.Confidence.HIGH.equals(band(f.ruleId(), f.anchored()))) return f;
        }
        return findings.get(0);
    }

    static String band(String rule, boolean anchored) {
        return anchored && !LOW_BAND_RULES.contains(rule) ? Detection.Confidence.HIGH : Detection.Confidence.LOW;
    }

    /** What {@link #Source} says about whether the credential itself is still sitting in stored output. */
    private static String storedAs(String source) {
        return Source.OUTPUT.equals(source) ? Stored.RAW : Stored.REDACTED;
    }

    /** Evidence's {@code stored} member: whether the credential itself is still sitting in stored output. */
    static final class Stored {
        private Stored() {}

        static final String REDACTED = "redacted";
        static final String RAW = "raw";
    }

    private Detection fired(String rule, String confidence, String source, @Nullable String masked) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("pattern", rule);
        evidence.put("source", source);
        if (masked != null) evidence.put("masked", masked);
        evidence.put("stored", storedAs(source));
        return Detection.fired(
                Detection.Severity.CRITICAL, mapper.valueToTree(evidence).toString(), confidence);
    }
}

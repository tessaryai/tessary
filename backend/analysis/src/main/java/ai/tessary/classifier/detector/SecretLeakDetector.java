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
 * The agent's OUTPUT carried a credential; a user pasting one is not claimed. Reads the redaction stamp, then the raw
 * output (for projects with redaction off), then a bare redaction token. HIGH means a rule anchored on a
 * provider-stamped literal; only HIGH counts toward arming. Evidence names the rule and a masked key, never the
 * credential; {@code config_json} is ignored.
 */
public final class SecretLeakDetector implements BuiltInDetector {

    /**
     * Precise shapes that are not reliably leaks: JWTs in docs, {@code Authorization} headers in {@code curl} examples,
     * and Kubernetes {@code kind: Secret} manifests with placeholder data.
     */
    static final Set<String> LOW_BAND_RULES =
            Set.of("jwt", "jwt-base64", "curl-auth-header", "curl-auth-user", "kubernetes-secret-yaml");

    /** All LOW: a token says something was removed but not what. */
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

        // The RAW output, not the flattened text: tool-call arguments and structured parts count too. Credential shapes
        // are alphanumeric, so JSON escaping does not hide them.
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

    private static String storedAs(String source) {
        return Source.OUTPUT.equals(source) ? Stored.RAW : Stored.REDACTED;
    }

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

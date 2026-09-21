// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.llm.decisions.DecisionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one question the Frustration classifier asks the decision model about a user turn, and the
 * threshold it fires at.
 *
 * <p>A three-way choice over the current message: unhappy with the assistant, unhappy about something
 * else, or neither. Only the first fires. The instruction is the shortest wording that held its recall
 * against the full one on both research sets, so it stays short; every edit here changes
 * {@link #scorerVersion} and so starts a new set of assessment rows.
 */
public final class JevFrustrationQuestion {

    /** The question's name in the request and the answer. */
    public static final String NAME = "user_stance";

    /** The option whose probability is the score. */
    public static final String UNHAPPY_WITH_ASSISTANT = "unhappy_with_assistant";

    public static final String UNHAPPY_OTHER_CAUSE = "unhappy_other_cause";
    public static final String NEUTRAL_OR_POSITIVE = "neutral_or_positive";

    // EXPERIMENT(frustration-tuning): the cutoff with a 1.35% false-flag rate per calm turn on the
    // research grade set, where it kept 59% recall at 74% precision. Per-project config overrides it.
    public static final double DEFAULT_THRESHOLD = 0.40;

    static final String INSTRUCTION = """
            Judge only current_user_message; earlier_messages are the turns before it, oldest first. \
            Unhappy means frustration, disappointment or hostility caused by the assistant, visible in the \
            user's own words or in re-asking after a clear failure. Emotion inside pasted or requested \
            content does not count. If unsure, pick neutral_or_positive. Which describes the current message?""";

    static final Map<String, String> CRITERIA = criteria();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JevFrustrationQuestion() {}

    private static Map<String, String> criteria() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put(UNHAPPY_WITH_ASSISTANT, "Visibly frustrated, disappointed, or hostile because of the assistant.");
        c.put(UNHAPPY_OTHER_CAUSE, "Negative, but about something outside the chat.");
        c.put(NEUTRAL_OR_POSITIVE, "Neutral, positive, only confused or urgent, or too ambiguous to say.");
        return c;
    }

    /** The questions sent with every turn. */
    public static Map<String, DecisionRequest.Question> questions() {
        return Map.of(NAME, DecisionRequest.Question.choice(INSTRUCTION, CRITERIA));
    }

    /**
     * The flag threshold from a classifier's {@code config_json} ({@code threshold}), or
     * {@link #DEFAULT_THRESHOLD} when it is absent, unreadable, or outside {@code (0, 1)}.
     */
    public static double threshold(@Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return DEFAULT_THRESHOLD;
        try {
            JsonNode t = MAPPER.readTree(configJson).path("threshold");
            if (t.isNumber() && t.doubleValue() > 0 && t.doubleValue() < 1) return t.doubleValue();
        } catch (JsonProcessingException e) {
            return DEFAULT_THRESHOLD;
        }
        return DEFAULT_THRESHOLD;
    }

    /**
     * What produced an assessment: a hash of the question as sent (its name, instruction and criteria)
     * and the threshold, since a flag under one threshold and under another are different events. The
     * model version is deliberately not in it, so a provider moving {@code jev-latest} does not restart
     * every call site.
     */
    public static String scorerVersion(double threshold) {
        try {
            String question = MAPPER.writeValueAsString(questions());
            String material = question + "|" + String.format(Locale.ROOT, "%.4f", threshold);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "jev-choice3-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash the frustration question", e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.llm.decisions.DecisionRequest;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import ai.tessary.open.hash.Sha256;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The question the Frustration classifier asks about a user turn: unhappy with the assistant, about something else, or
 * neither; only the first fires. The shortest wording that held recall; every edit changes {@link #scorerVersion} and
 * starts new assessment rows.
 */
public final class JevFrustrationQuestion {

    public static final String NAME = "user_stance";

    /** Its probability is the score. */
    public static final String UNHAPPY_WITH_ASSISTANT = "unhappy_with_assistant";

    public static final String UNHAPPY_OTHER_CAUSE = "unhappy_other_cause";
    public static final String NEUTRAL_OR_POSITIVE = "neutral_or_positive";

    // EXPERIMENT(frustration-tuning): the cutoff with a 1.35% false-flag rate per calm turn on the
    // research grade set, where it kept 59% recall at 74% precision. Per-project config overrides it.
    public static final double DEFAULT_THRESHOLD = 0.40;

    /**
     * Keyed on session: a thread-id key read prior turns from other sessions and silenced a user's later sessions after
     * one flag.
     */
    private static final String CONVERSATION_KEY = "conversation=session_id";

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

    public static Map<String, DecisionRequest.Question> questions() {
        return Map.of(NAME, DecisionRequest.Question.choice(INSTRUCTION, CRITERIA));
    }

    /** {@link #DEFAULT_THRESHOLD} when absent, unreadable, or outside {@code (0, 1)}. */
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
     * A hash of the question as sent, the threshold and the conversation key. The model version is excluded so a
     * provider moving {@code jev-latest} does not restart every call site.
     */
    public static String scorerVersion(double threshold) {
        String material =
                questionsJson() + "|" + String.format(Locale.ROOT, "%.4f", threshold) + "|" + CONVERSATION_KEY;
        return "jev-choice3-" + HexFormat.of().formatHex(Sha256.digest(material), 0, 6);
    }

    @ExcludeFromJacocoGeneratedReport("serializes a fixed list of strings, so the checked catch cannot fire")
    private static String questionsJson() {
        try {
            return MAPPER.writeValueAsString(questions());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot hash the frustration question", e);
        }
    }
}

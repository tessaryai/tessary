// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Frustration classifier's operating point: the flag threshold its scorer uses, and the dials of the rate
 * test that watches frustrated conversations per call site. Its own record with its own key names, clamped on
 * construction, because the rate test counts conversations where tool_error counts tool calls: the same number
 * means a different amount of traffic. {@link #engine()} is the {@link ToolErrorConfig} the shared engine runs
 * on. The arithmetic and the table behind these defaults are in {@code devdocs/concepts/deviation-math.md} §2.
 *
 * @param threshold probability of {@code unhappy_with_assistant} above which a turn is flagged
 * @param arlTarget conversations a healthy call site should run between false alarms
 * @param minDecisionInterval the lower clamp on the derived alarm threshold {@code h}
 * @param shiftMultiple the multiple of the learned rate the test is tuned to catch quickly
 * @param shiftFloor the smallest absolute rise in the frustrated-conversation rate worth arming for
 * @param minBaselineConversations conversations the learned reference must hold before anything is judged
 */
public record FrustrationConfig(
        double threshold,
        long arlTarget,
        double minDecisionInterval,
        double shiftMultiple,
        double shiftFloor,
        int minBaselineConversations) {

    /**
     * EXPERIMENT(frustration-tuning): conversations a healthy call site runs between false alarms. A starting
     * value: tool_error's 250,000 tool calls converted at 25 to 50 spans a conversation, taking the
     * conservative end. At 200 conversations a day that is one false case per call site every seven weeks.
     */
    public static final long DEFAULT_ARL_TARGET = 10_000L;

    /**
     * EXPERIMENT(frustration-tuning): the floor on {@code h}. A starting value. With tool_error's 6 every call
     * site below a 13% base rate would run several times stricter than the budget; the fit crosses 4 near 2%,
     * so this floor binds only below that.
     */
    public static final double DEFAULT_MIN_DECISION_INTERVAL = 4.0;

    /** EXPERIMENT(frustration-tuning): a doubling, as tool_error. */
    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /**
     * EXPERIMENT(frustration-tuning): two conversations in a hundred, which is also the scorer's own noise
     * floor per calm conversation. A smaller floor would arm a clean call site for a rise nobody can tell
     * from the scorer's false flags.
     */
    public static final double DEFAULT_SHIFT_FLOOR = 0.02;

    /**
     * EXPERIMENT(frustration-tuning): conversations the reference learns from before it is frozen. At a 5%
     * rate that pins the reference at least as tightly, relatively, as tool_error's 500 calls pin a 1% tool.
     */
    public static final int DEFAULT_MIN_BASELINE_CONVERSATIONS = 200;

    public FrustrationConfig {
        threshold = threshold > 0 && threshold < 1 ? threshold : JevFrustrationQuestion.DEFAULT_THRESHOLD;
        arlTarget = arlTarget <= 0 ? DEFAULT_ARL_TARGET : Math.max(1_000L, Math.min(1_000_000_000L, arlTarget));
        minDecisionInterval = !Double.isFinite(minDecisionInterval) || minDecisionInterval <= 0
                ? DEFAULT_MIN_DECISION_INTERVAL
                : Math.max(
                        ToolErrorConfig.LOWEST_DECISION_INTERVAL,
                        Math.min(ToolErrorConfig.MAX_DECISION_INTERVAL, minDecisionInterval));
        shiftMultiple = !Double.isFinite(shiftMultiple) || shiftMultiple <= 0
                ? DEFAULT_SHIFT_MULTIPLE
                : Math.max(1.05, Math.min(100.0, shiftMultiple));
        shiftFloor = !Double.isFinite(shiftFloor) || shiftFloor <= 0
                ? DEFAULT_SHIFT_FLOOR
                : Math.max(0.0001, Math.min(0.5, shiftFloor));
        minBaselineConversations = minBaselineConversations <= 0
                ? DEFAULT_MIN_BASELINE_CONVERSATIONS
                : Math.max(30, Math.min(1_000_000, minBaselineConversations));
    }

    public static FrustrationConfig defaults() {
        return new FrustrationConfig(
                JevFrustrationQuestion.DEFAULT_THRESHOLD,
                DEFAULT_ARL_TARGET,
                DEFAULT_MIN_DECISION_INTERVAL,
                DEFAULT_SHIFT_MULTIPLE,
                DEFAULT_SHIFT_FLOOR,
                DEFAULT_MIN_BASELINE_CONVERSATIONS);
    }

    /**
     * Parse a classifier's blob, falling back to the default for any key it does not carry and to every
     * default for a blob that does not parse, as {@link ToolErrorConfig#of} does.
     */
    public static FrustrationConfig of(ObjectMapper mapper, @Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return defaults();
        try {
            JsonNode root = mapper.readTree(configJson);
            return new FrustrationConfig(
                    JevFrustrationQuestion.threshold(configJson),
                    root.path("arl_target").asLong(DEFAULT_ARL_TARGET),
                    root.path("min_decision_interval").asDouble(DEFAULT_MIN_DECISION_INTERVAL),
                    root.path("shift_multiple").asDouble(DEFAULT_SHIFT_MULTIPLE),
                    root.path("shift_floor").asDouble(DEFAULT_SHIFT_FLOOR),
                    root.path("min_baseline_conversations").asInt(DEFAULT_MIN_BASELINE_CONVERSATIONS));
        } catch (JsonProcessingException e) {
            return defaults();
        }
    }

    /**
     * The shared engine's config. The keys this classifier does not set stay at the engine's defaults; none of
     * them affects the up arm, which is the only one filed.
     */
    public ToolErrorConfig engine() {
        return new ToolErrorConfig(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineConversations,
                ToolErrorConfig.DEFAULT_DOWN_ARM_MIN_RATE,
                ToolErrorConfig.DEFAULT_MAX_PATTERNS,
                minDecisionInterval);
    }

    /** The hash of the question and the threshold every assessment row this config scores carries. */
    public String scorerVersion() {
        return JevFrustrationQuestion.scorerVersion(threshold);
    }

    /**
     * The schema version handed to {@link ToolErrorTrend#sweep(java.util.List, ToolErrorConfig, java.util.Map,
     * java.util.Map, String)}: the engine's own, plus what the engine's epoch does not already carry. A flag
     * under one scorer and under another are different events, and a different floor is a different threshold.
     */
    public String schemaVersion() {
        return String.join(
                "|",
                ToolErrorTrend.STATE_SCHEMA_VERSION,
                "frustration",
                String.format(Locale.ROOT, "%.2f", minDecisionInterval),
                scorerVersion());
    }

    /** The epoch a {@code frustration_state} row built under this config carries. */
    public String stateEpoch() {
        return CarriedState.epochOf(engine(), schemaVersion());
    }
}

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
 * The Frustration classifier's operating point, clamped on construction. Its own keys because the rate test counts
 * conversations where tool_error counts tool calls; defaults are derived in {@code devdocs/concepts/deviation-math.md}
 * §2. @param freezeBaselineConversations never below {@code minBaselineConversations}, and equal to it when the blob
 * does not name one
 */
public record FrustrationConfig(
        double threshold,
        long arlTarget,
        double minDecisionInterval,
        double shiftMultiple,
        double shiftFloor,
        int minBaselineConversations,
        int freezeBaselineConversations) {

    /**
     * EXPERIMENT(frustration-tuning): tool_error's 250,000 calls at 25-50 spans a conversation, conservative end; one
     * false case per call site every seven weeks at 200 conversations a day.
     */
    public static final long DEFAULT_ARL_TARGET = 10_000L;

    /**
     * EXPERIMENT(frustration-tuning): floor on {@code h}; the fit crosses 4 near a 2% base rate, so it binds only below
     * that.
     */
    public static final double DEFAULT_MIN_DECISION_INTERVAL = 4.0;

    /** EXPERIMENT(frustration-tuning): a doubling, as tool_error. */
    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /** EXPERIMENT(frustration-tuning): also the scorer's own noise floor per calm conversation. */
    public static final double DEFAULT_SHIFT_FLOOR = 0.02;

    /**
     * EXPERIMENT(frustration-tuning): at a 5% rate this pins the reference as tightly as tool_error's 500 calls pin a
     * 1% tool.
     */
    public static final int DEFAULT_MIN_BASELINE_CONVERSATIONS = 100;

    /**
     * A blob without {@code freeze_baseline_conversations} freezes at judging start, as rows did before the key
     * existed, so they keep their state epoch.
     */
    private static final int UNSET_FREEZE = 0;

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
        freezeBaselineConversations = freezeBaselineConversations <= 0
                ? minBaselineConversations
                : Math.max(minBaselineConversations, Math.min(1_000_000, freezeBaselineConversations));
    }

    public static FrustrationConfig defaults() {
        return new FrustrationConfig(
                JevFrustrationQuestion.DEFAULT_THRESHOLD,
                DEFAULT_ARL_TARGET,
                DEFAULT_MIN_DECISION_INTERVAL,
                DEFAULT_SHIFT_MULTIPLE,
                DEFAULT_SHIFT_FLOOR,
                DEFAULT_MIN_BASELINE_CONVERSATIONS,
                UNSET_FREEZE);
    }

    /** Falls back to the default per missing key, and to all defaults for an unparseable blob. */
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
                    root.path("min_baseline_conversations").asInt(DEFAULT_MIN_BASELINE_CONVERSATIONS),
                    root.path("freeze_baseline_conversations").asInt(UNSET_FREEZE));
        } catch (JsonProcessingException e) {
            return defaults();
        }
    }

    /** Unset keys stay at the engine's defaults; none affects the up arm, the only one filed. */
    public ToolErrorConfig engine() {
        return new ToolErrorConfig(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineConversations,
                ToolErrorConfig.DEFAULT_DOWN_ARM_MIN_RATE,
                ToolErrorConfig.DEFAULT_MAX_PATTERNS,
                minDecisionInterval,
                freezeBaselineConversations);
    }

    /** The hash of the question and the threshold every assessment row this config scores carries. */
    public String scorerVersion() {
        return JevFrustrationQuestion.scorerVersion(threshold);
    }

    /** The engine's schema version plus the scorer and floor, which the engine's epoch does not carry. */
    public String schemaVersion() {
        return String.join(
                "|",
                ToolErrorTrend.STATE_SCHEMA_VERSION,
                "frustration",
                String.format(Locale.ROOT, "%.2f", minDecisionInterval),
                scorerVersion());
    }

    public String stateEpoch() {
        return CarriedState.epochOf(engine(), schemaVersion());
    }
}

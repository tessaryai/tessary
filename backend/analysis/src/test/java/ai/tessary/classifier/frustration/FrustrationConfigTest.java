// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrustrationConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anAbsentOrUnreadableBlobIsTheDefaults() {
        FrustrationConfig d = FrustrationConfig.defaults();
        assertEquals(d, FrustrationConfig.of(MAPPER, null));
        assertEquals(d, FrustrationConfig.of(MAPPER, "{not json"));
        assertEquals(d, FrustrationConfig.of(MAPPER, "{\"cold_start_turn_fpr\":0.01}"));
    }

    @Test
    void clampsEveryDial() {
        FrustrationConfig c = FrustrationConfig.of(MAPPER, """
                {"threshold":1.5,"arl_target":5,"min_decision_interval":20,
                 "shift_multiple":1.0,"shift_floor":0.9,"min_baseline_conversations":3}
                """);
        assertEquals(0.40, c.threshold(), "outside (0, 1) falls back to the default");
        assertEquals(1_000L, c.arlTarget());
        assertEquals(12.0, c.minDecisionInterval());
        assertEquals(1.05, c.shiftMultiple());
        assertEquals(0.5, c.shiftFloor());
        assertEquals(30, c.minBaselineConversations());
        assertEquals(
                2.0,
                FrustrationConfig.of(MAPPER, "{\"min_decision_interval\":1}").minDecisionInterval());
        assertEquals(
                300,
                FrustrationConfig.of(
                                MAPPER, "{\"min_baseline_conversations\":300,\"freeze_baseline_conversations\":50}")
                        .freezeBaselineConversations(),
                "never below the minimum");
    }

    /** The seeded tuning reaches the engine: judged from 100, the reference learning until 1,000. */
    @Test
    void theEngineRunsOnTheParsedLearningSpan() {
        FrustrationConfig c = FrustrationConfig.of(
                MAPPER, "{\"min_baseline_conversations\":100,\"freeze_baseline_conversations\":1000}");

        assertEquals(100, c.engine().minBaselineCalls());
        assertEquals(1_000, c.engine().freezeBaselineCalls());
        assertTrue(c.engine().learnsWhileJudging());
    }

    @Test
    void theEpochCarriesTheScorerVersionAndTheFloor() {
        FrustrationConfig d = FrustrationConfig.defaults();
        assertTrue(d.stateEpoch().contains(d.scorerVersion()));
        assertEquals(JevFrustrationQuestion.scorerVersion(0.40), d.scorerVersion());

        FrustrationConfig otherThreshold = FrustrationConfig.of(MAPPER, "{\"threshold\":0.5}");
        assertNotEquals(d.stateEpoch(), otherThreshold.stateEpoch(), "a flag under 0.40 and 0.50 differ");
        FrustrationConfig otherFloor = FrustrationConfig.of(MAPPER, "{\"min_decision_interval\":5}");
        assertNotEquals(d.stateEpoch(), otherFloor.stateEpoch());
        FrustrationConfig otherTarget = FrustrationConfig.of(MAPPER, "{\"arl_target\":20000}");
        assertNotEquals(d.stateEpoch(), otherTarget.stateEpoch());
        assertEquals(
                d.stateEpoch(),
                FrustrationConfig.of(MAPPER, "{\"min_baseline_conversations\":300}")
                        .stateEpoch());
    }

    /**
     * A dial that is zero, negative or not finite takes its default rather than the clamp: {@code Math.max} and
     * {@code Math.min} pass a NaN straight through, and a NaN floor or multiple would leave the rate test
     * unable to arm. A threshold outside (0, 1) and a non-positive count take theirs too.
     */
    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void aNonPositiveOrNonFiniteDialIsItsDefault(double bad) {
        assertEquals(FrustrationConfig.defaults(), new FrustrationConfig(bad, 0L, bad, bad, bad, 0, 0, -1));
    }

    /**
     * A stored accumulator and reference counted conversations at their first scored turn. Resumed under the
     * conversation-hour trial, they would judge new hours against a rate of another unit; a blob that pins
     * {@code reference_settle_hours} would keep its epoch if only the settle default moved.
     */
    @Test
    void theEpochChangesWithTheTrial() {
        FrustrationConfig pinned = FrustrationConfig.of(MAPPER, "{\"reference_settle_hours\":24}");
        String sessionTrial = CarriedState.epochOf(
                pinned.engine(),
                String.join(
                        "|",
                        ToolErrorTrend.STATE_SCHEMA_VERSION,
                        "frustration",
                        String.format(Locale.ROOT, "%.2f", pinned.minDecisionInterval()),
                        pinned.scorerVersion()));

        assertNotEquals(sessionTrial, pinned.stateEpoch());
        assertTrue(pinned.schemaVersion().contains("|frustration-hour|"), pinned.schemaVersion());
    }

    /** An hour's count is final once its turns are scored: one hour covers the scorer's lag, as tool_error. */
    @Test
    void engine_carriesTheClassifiersSettleDelay() {
        assertEquals(1, FrustrationConfig.defaults().engine().referenceSettleHours());
        assertEquals(
                12,
                FrustrationConfig.of(MAPPER, "{\"reference_settle_hours\":12}")
                        .engine()
                        .referenceSettleHours());
    }
}

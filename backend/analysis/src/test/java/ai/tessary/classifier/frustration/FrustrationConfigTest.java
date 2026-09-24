// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.ToolErrorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FrustrationConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesItsOwnKeys() {
        FrustrationConfig c = FrustrationConfig.of(MAPPER, """
                {"threshold":0.5,"arl_target":20000,"min_decision_interval":5,
                 "shift_multiple":3.0,"shift_floor":0.03,"min_baseline_conversations":300}
                """);
        assertEquals(0.5, c.threshold());
        assertEquals(20_000L, c.arlTarget());
        assertEquals(5.0, c.minDecisionInterval());
        assertEquals(3.0, c.shiftMultiple());
        assertEquals(0.03, c.shiftFloor());
        assertEquals(300, c.minBaselineConversations());
    }

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
    }

    @Test
    void buildsTheEngineConfigWithAFloorOfFour() {
        ToolErrorConfig engine = FrustrationConfig.defaults().engine();
        assertEquals(4.0, engine.minDecisionInterval());
        assertEquals(10_000L, engine.arlTarget());
        assertEquals(200, engine.minBaselineCalls());
        assertEquals(0.02, engine.shiftFloor());
        assertEquals(4.94, engine.decisionIntervalFor(0.05), 0.005);
        assertEquals(4.00, engine.decisionIntervalFor(0.01), 1e-12);
        assertEquals(5.70, engine.decisionIntervalFor(0.10), 0.005);
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
}

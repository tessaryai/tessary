// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.ToolErrorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GroundednessConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesItsOwnKeys() {
        GroundednessConfig c = GroundednessConfig.of(MAPPER, """
                {"threshold":0.9,"arl_target":20000,"min_decision_interval":5,
                 "shift_multiple":3.0,"shift_floor":0.03,"min_baseline_traces":300,"freeze_baseline_traces":2000}
                """);
        assertEquals(0.9, c.threshold());
        assertEquals(20_000L, c.arlTarget());
        assertEquals(5.0, c.minDecisionInterval());
        assertEquals(3.0, c.shiftMultiple());
        assertEquals(0.03, c.shiftFloor());
        assertEquals(300, c.minBaselineTraces());
        assertEquals(2_000, c.freezeBaselineTraces());
    }

    @Test
    void anAbsentOrUnreadableBlobIsTheDefaults() {
        GroundednessConfig d = GroundednessConfig.defaults();
        assertEquals(d, GroundednessConfig.of(MAPPER, null));
        assertEquals(d, GroundednessConfig.of(MAPPER, "{not json"));
        assertEquals(d, GroundednessConfig.of(MAPPER, "{\"arming\":{\"threshold\":3}}"));
    }

    @Test
    void anOlderBlobsHighBandIsTheThresholdOnlyWhenThresholdIsAbsent() {
        assertEquals(
                0.9,
                GroundednessConfig.of(MAPPER, "{\"threshold_high\":0.9,\"threshold_low\":0.5}")
                        .threshold());
        assertEquals(
                0.95,
                GroundednessConfig.of(MAPPER, "{\"threshold\":0.95,\"threshold_high\":0.9}")
                        .threshold());
    }

    @Test
    void clampsEveryDial() {
        GroundednessConfig c = GroundednessConfig.of(MAPPER, """
                {"threshold":1.5,"arl_target":5,"min_decision_interval":20,
                 "shift_multiple":1.0,"shift_floor":0.9,"min_baseline_traces":3,"freeze_baseline_traces":10}
                """);
        assertEquals(0.975, c.threshold(), "outside (0, 1) falls back to the default");
        assertEquals(1_000L, c.arlTarget());
        assertEquals(12.0, c.minDecisionInterval());
        assertEquals(1.05, c.shiftMultiple());
        assertEquals(0.5, c.shiftFloor());
        assertEquals(30, c.minBaselineTraces());
        assertEquals(30, c.freezeBaselineTraces(), "never below the minimum");
        assertEquals(
                2.0,
                GroundednessConfig.of(MAPPER, "{\"min_decision_interval\":1}").minDecisionInterval());
        assertEquals(
                5_000,
                GroundednessConfig.of(MAPPER, "{\"min_baseline_traces\":5000}").freezeBaselineTraces(),
                "an absent freeze below a raised minimum is raised with it");
    }

    @Test
    void buildsTheEngineConfigThatJudgesFrom200AndLearnsTo1000() {
        ToolErrorConfig engine = GroundednessConfig.defaults().engine();
        assertEquals(4.0, engine.minDecisionInterval());
        assertEquals(50_000L, engine.arlTarget());
        assertEquals(200, engine.minBaselineCalls());
        assertEquals(1_000, engine.freezeBaselineCalls());
        assertEquals(0.02, engine.shiftFloor());
        assertEquals(2.0, engine.shiftMultiple());
    }

    @Test
    void theEpochCarriesTheScorerVersionTheFloorAndTheLearningSpan() {
        GroundednessConfig d = GroundednessConfig.defaults();
        assertTrue(d.stateEpoch().contains(d.scorerVersion()));
        assertEquals(GroundednessDetector.scorerVersion(0.975), d.scorerVersion());

        GroundednessConfig otherThreshold = GroundednessConfig.of(MAPPER, "{\"threshold\":0.9}");
        assertNotEquals(d.stateEpoch(), otherThreshold.stateEpoch(), "a flag under 0.975 and 0.9 differ");
        GroundednessConfig otherFloor = GroundednessConfig.of(MAPPER, "{\"min_decision_interval\":5}");
        assertNotEquals(d.stateEpoch(), otherFloor.stateEpoch());
        GroundednessConfig otherTarget = GroundednessConfig.of(MAPPER, "{\"arl_target\":20000}");
        assertNotEquals(d.stateEpoch(), otherTarget.stateEpoch());
        // The reference learns while it judges, so where learning starts and stops is part of what a
        // judged hour meant.
        GroundednessConfig otherFreeze = GroundednessConfig.of(MAPPER, "{\"freeze_baseline_traces\":2000}");
        assertNotEquals(d.stateEpoch(), otherFreeze.stateEpoch());
    }
}

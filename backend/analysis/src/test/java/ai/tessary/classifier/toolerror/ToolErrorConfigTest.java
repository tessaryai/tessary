// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The threshold floor became a per-classifier value for frustration. tool_error's must not move: its
 * eight-argument shape keeps 6, and no tool-error blob can lower it.
 */
class ToolErrorConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The derivation as it was before the floor became a component. */
    private static double oldDecisionInterval(double p0) {
        double h = ToolErrorConfig.ARL_FIT_INTERCEPT + ToolErrorConfig.ARL_FIT_SLOPE * Math.log(p0);
        return Math.max(6.0, Math.min(12.0, h));
    }

    @Test
    void theEightArgumentShapeKeepsTheFloorAtSix() {
        ToolErrorConfig c = new ToolErrorConfig(250_000L, 2.0, 0.005, 0.05, 500, 0.01, 300, 8);
        assertEquals(6.0, c.minDecisionInterval());
        assertEquals(6.0, ToolErrorConfig.defaults().minDecisionInterval());
    }

    @Test
    void toolErrorsDecisionIntervalIsUnchanged() {
        ToolErrorConfig c = ToolErrorConfig.defaults();
        for (double p0 : new double[] {0.005, 0.01, 0.05, 0.20}) {
            assertEquals(oldDecisionInterval(p0), c.decisionIntervalFor(p0), 1e-12, "p0=" + p0);
        }
        assertEquals(6.0, c.decisionIntervalFor(0.005), "the floor binds at half a percent");
        assertEquals(6.0, c.decisionIntervalFor(0.0), "an unusable rate falls to the floor");
    }

    @Test
    void aToolErrorBlobCannotLowerTheFloor() {
        ToolErrorConfig c = ToolErrorConfig.of(MAPPER, "{\"min_decision_interval\": 2, \"arl_target\": 250000}");
        assertEquals(6.0, c.minDecisionInterval());
        assertEquals(6.0, c.decisionIntervalFor(0.005));
    }

    @Test
    void anExplicitFloorIsClampedAndUsed() {
        ToolErrorConfig low = new ToolErrorConfig(10_000L, 2.0, 0.02, 0.05, 200, 0.01, 300, 8, 0.5);
        assertEquals(ToolErrorConfig.LOWEST_DECISION_INTERVAL, low.minDecisionInterval());
        ToolErrorConfig high = new ToolErrorConfig(10_000L, 2.0, 0.02, 0.05, 200, 0.01, 300, 8, 40.0);
        assertEquals(ToolErrorConfig.MAX_DECISION_INTERVAL, high.minDecisionInterval());
        ToolErrorConfig four = new ToolErrorConfig(10_000L, 2.0, 0.02, 0.05, 200, 0.01, 300, 8, 4.0);
        assertEquals(4.0, four.decisionIntervalFor(0.01), "below the fit the per-classifier floor binds");
        assertEquals(4.0, four.decisionIntervalFor(0.0));
    }
}

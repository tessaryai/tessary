// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
        ToolErrorConfig low = new ToolErrorConfig(10_000L, 2.0, 0.02, 200, 0.01, 8, 0.5);
        assertEquals(ToolErrorConfig.LOWEST_DECISION_INTERVAL, low.minDecisionInterval());
        ToolErrorConfig high = new ToolErrorConfig(10_000L, 2.0, 0.02, 200, 0.01, 8, 40.0);
        assertEquals(ToolErrorConfig.MAX_DECISION_INTERVAL, high.minDecisionInterval());
        ToolErrorConfig four = new ToolErrorConfig(10_000L, 2.0, 0.02, 200, 0.01, 8, 4.0);
        assertEquals(4.0, four.decisionIntervalFor(0.01), "below the fit the per-classifier floor binds");
        assertEquals(4.0, four.decisionIntervalFor(0.0));
    }

    @Test
    void freezeIsClampedToAtLeastTheMinimum() {
        ToolErrorConfig below = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0, 100);
        assertEquals(200, below.freezeBaselineCalls(), "a reference cannot stop learning before judging starts");
        ToolErrorConfig unset = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0);
        assertEquals(200, unset.freezeBaselineCalls(), "unset freezes it the moment judging starts");
        ToolErrorConfig huge = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0, 5_000_000);
        assertEquals(1_000_000, huge.freezeBaselineCalls());
        assertEquals(
                ToolErrorConfig.DEFAULT_MIN_BASELINE_CALLS,
                ToolErrorConfig.defaults().freezeBaselineCalls(),
                "every existing classifier freezes where it always did");

        ToolErrorConfig parsed =
                ToolErrorConfig.of(MAPPER, "{\"min_baseline_calls\": 200, \"freeze_baseline_calls\": 1000}");
        assertEquals(200, parsed.minBaselineCalls());
        assertEquals(1000, parsed.freezeBaselineCalls());
        assertEquals(
                700, ToolErrorConfig.of(MAPPER, "{\"min_baseline_calls\": 700}").freezeBaselineCalls());
    }

    @Test
    void theFreezeIsInTheEpoch() {
        String schema = ToolErrorTrend.STATE_SCHEMA_VERSION;
        ToolErrorConfig toOneThousand = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0, 1000);
        ToolErrorConfig toTwoThousand = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0, 2000);
        ToolErrorConfig judgedFromThree = new ToolErrorConfig(50_000L, 2.0, 0.02, 300, 0.01, 8, 4.0, 1000);
        ToolErrorConfig frozen = new ToolErrorConfig(50_000L, 2.0, 0.02, 200, 0.01, 8, 4.0);

        assertNotEquals(CarriedState.epochOf(toOneThousand, schema), CarriedState.epochOf(toTwoThousand, schema));
        assertNotEquals(CarriedState.epochOf(toOneThousand, schema), CarriedState.epochOf(judgedFromThree, schema));
        assertNotEquals(CarriedState.epochOf(toOneThousand, schema), CarriedState.epochOf(frozen, schema));

        // A reference frozen when judging starts leaves the epoch as it was, so no stored row rebuilds for it.
        ToolErrorConfig d = ToolErrorConfig.defaults();
        assertEquals(
                String.join(
                        "|",
                        schema,
                        Long.toString(d.arlTarget()),
                        Double.toString(d.shiftMultiple()),
                        Double.toString(d.shiftFloor()),
                        Double.toString(d.downArmMinRate())),
                CarriedState.epochOf(d, schema));
    }

    /**
     * A blob that is absent or will not parse runs at the shipped operating point: a blob written by a newer
     * build must not dead-letter an older build's sweep.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "{not json"})
    void anAbsentOrUnreadableBlobIsTheDefaults(@Nullable String blob) {
        assertEquals(ToolErrorConfig.defaults(), ToolErrorConfig.of(MAPPER, blob));
    }

    /**
     * A dial that is zero, negative or not a number falls back to its default instead of being clamped: a NaN
     * slips through {@code Math.max}/{@code Math.min} unchanged and would leave the test unable to arm.
     */
    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void aNonPositiveOrNonFiniteDialIsItsDefault(double bad) {
        ToolErrorConfig c = new ToolErrorConfig(0L, bad, bad, 0, bad, 0);
        assertEquals(ToolErrorConfig.defaults(), c);
    }
}

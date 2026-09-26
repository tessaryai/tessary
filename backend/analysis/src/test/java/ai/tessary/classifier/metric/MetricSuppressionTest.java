// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricSuppression.Explanation;
import ai.tessary.classifier.metric.MetricSuppression.Shift;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The §6.1 cross-grain rule, driven directly.
 *
 * <p>The first test matters most: suppression fails by swallowing a turn that got slower because the agent did more,
 * with every tool as fast as ever. No tool finding exists for it, and it is the reason turn duration is measured at
 * all. The bar ({@link MetricDriftConfig#DEFAULT_EXPLAINED_BY_FRACTION}) is loose, but it has a bottom: 40ms of tool
 * movement explains nothing of a 4s turn move.
 */
class MetricSuppressionTest {

    private static final MetricDriftConfig CONFIG = MetricDriftConfig.defaults();

    private static final String CALL_SITE = "discover-sales-prospects";
    private static final String OTHER_CALL_SITE = "summarize-thread";

    @Test
    @DisplayName("a tool that moved 40ms inside a turn that moved 4s has explained nothing")
    void aTinyToolShiftDoesNotExplainALargeTurnShift() {
        // A real 3x tool shift, but the floor is a ratio and the accounting is absolute time: 40ms of a 4,000ms move
        // is 1%.
        Shift turn = turnShift(2_000, 6_000);
        Shift tool = toolShift("tool:lookup_id", 20, 60, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool that got FASTER does not explain a turn that got slower")
    void anOppositeMoveIsNotAnExplanation() {
        // A tool speeding up while its caller slows is two findings: the time went somewhere the tool grain cannot
        // see.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 1_500, 500, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool shift measured over someone else's traffic explains nothing here")
    void aShiftInADifferentCallSiteIsNotAnExplanation() {
        // Tool buckets are not per call site, so matching uses the window's own traffic, or an unrelated call site's
        // tool would silence a real turn regression.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 500, 2_500, OTHER_CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool used by both call sites explains the one whose traffic it was measured over")
    void aSharedToolMatchesOnAnyContributingCallSite() {
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = new Shift(
                "tool:search_docs",
                Set.of(OTHER_CALL_SITE, CALL_SITE),
                fired(Measure.TOOL_DURATION, Direction.UP),
                500,
                2_500);

        Explanation explanation = MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction());

        assertNotNull(explanation);
        assertEquals("tool:search_docs", explanation.tool().bucketKey());
    }

    @Test
    @DisplayName("with several tools slowing at once, the one accounting for most time wins")
    void theBestExplanationIsTheOneCoveringMost() {
        // Point the reader at the tool owning most of the added time, not the first one folded.
        Shift turn = turnShift(2_000, 6_000);
        Shift small = toolShift("tool:lookup_id", 200, 2_400, CALL_SITE);
        Shift large = toolShift("tool:search_docs", 400, 3_600, CALL_SITE);

        Explanation explanation = MetricSuppression.explain(turn, List.of(small, large), CONFIG.explainedByFraction());

        assertNotNull(explanation);
        assertEquals("tool:search_docs", explanation.tool().bucketKey());
        assertEquals(0.8, explanation.covered(), 0.01);
    }

    @Test
    @DisplayName("a turn whose median did not move has nothing for a tool to account for")
    void aTurnWithNoMedianMovementIsNeverSuppressed() {
        // A tail-only shift has no added median time; dividing by that zero would clear every bar.
        Shift turn = turnShift(2_000, 2_000);
        Shift tool = toolShift("tool:search_docs", 400, 3_600, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tightened bar is what a swallowed regression is corrected with")
    void raisingTheFractionUnsuppresses() {
        // This number is the dial: same shifts, two bars, two answers.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 600, 1_800, CALL_SITE);

        assertNotNull(MetricSuppression.explain(turn, List.of(tool), 0.5));
        assertNull(MetricSuppression.explain(turn, List.of(tool), 0.75));
    }

    private static Shift turnShift(double refMillis, double curMillis) {
        return new Shift(
                CALL_SITE,
                Set.of(CALL_SITE),
                fired(Measure.TURN_DURATION, curMillis >= refMillis ? Direction.UP : Direction.DOWN),
                refMillis,
                curMillis);
    }

    private static Shift toolShift(String bucketKey, double refMillis, double curMillis, String callSite) {
        return new Shift(
                bucketKey,
                Set.of(callSite),
                fired(Measure.TOOL_DURATION, curMillis >= refMillis ? Direction.UP : Direction.DOWN),
                refMillis,
                curMillis);
    }

    /** A decision that fired. The rule reads the medians, not its ratio, so the ratio is nominal. */
    private static Decision fired(String measure, Direction direction) {
        double w1 = direction == Direction.UP ? 0.34 : -0.34;
        return new Decision(true, measure, Reference.PINNED, w1, Math.exp(w1), direction, 500, 500, 0.139, null);
    }
}

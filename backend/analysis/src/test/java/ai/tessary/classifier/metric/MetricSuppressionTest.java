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
 * The §6.1 cross-grain rule, driven directly — no Spring, no database, no clock.
 *
 * <p><b>The first test is the important one</b>, and it is first on purpose. Suppression exists to stop
 * one event being reported twice, and the way it fails is by suppressing an event that was never the
 * same one: a turn that got slower because the agent chose to do MORE, where every individual tool call
 * is exactly as fast as it always was. That case is invisible at tool grain by construction — no tool
 * bucket moved, so no tool finding exists to compare against — and it is the entire reason turn duration
 * is measured at all. A suppression rule that swallows it turns {@code duration_drift} into a classifier
 * that can only see slower tools, which is the smaller half of the question.
 *
 * <p>The bar is deliberately loose ({@link MetricDriftConfig#DEFAULT_EXPLAINED_BY_FRACTION}) because
 * partial explanation is still explanation and because suppressing costs nothing — the turn shift rides
 * on the tool finding as evidence either way. What the tests below pin is that "loose" still has a
 * bottom: a tool that moved forty milliseconds inside a turn that moved four seconds has explained
 * nothing, and says so.
 */
class MetricSuppressionTest {

    private static final MetricDriftConfig CONFIG = MetricDriftConfig.defaults();

    private static final String CALL_SITE = "discover-sales-prospects";
    private static final String OTHER_CALL_SITE = "summarize-thread";

    @Test
    @DisplayName("eleven tool calls where three used to do: no tool moved, so nothing suppresses the turn")
    void aTurnShiftWithNoToolShiftIsNotSuppressed() {
        // The agent decomposed the same request into more steps. Every call it makes is as fast as it
        // ever was, so not one tool bucket produced a shift at all and the candidate list is empty. This
        // is not an edge case — it is the regression class turn duration exists to catch, and the only
        // grain it is visible at.
        Shift turn = turnShift(2_000, 5_000);

        assertNull(MetricSuppression.explain(turn, List.of(), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool that moved 40ms inside a turn that moved 4s has explained nothing")
    void aTinyToolShiftDoesNotExplainALargeTurnShift() {
        // A real tool shift, firing on its own account — 20ms to 60ms is 3x and comfortably past the
        // floor — but the floor is a RATIO and the accounting is in absolute TIME. Three times nothing
        // is nothing: 40ms of a 4,000ms move is 1%, and reporting the turn as "explained by" it would
        // send whoever reads it to optimize the wrong thing.
        Shift turn = turnShift(2_000, 6_000);
        Shift tool = toolShift("tool:lookup_id", 20, 60, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool that accounts for most of the turn's added time suppresses it")
    void aToolShiftThatCoversTheTurnExplainsIt() {
        // 34 of the 38 seconds were one search_docs call. The turn row would only restate the symptom;
        // the tool row names the fix, and the turn shift rides on it as evidence.
        Shift turn = turnShift(4_000, 38_000);
        Shift tool = toolShift("tool:search_docs", 400, 34_400, CALL_SITE);

        Explanation explanation = MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction());

        assertNotNull(explanation);
        assertEquals("tool:search_docs", explanation.tool().bucketKey());
        assertEquals(1.0, explanation.covered(), 0.01);
    }

    @Test
    @DisplayName("partial explanation is still explanation: 60% of the turn's move is enough")
    void aPartialCoverageStillSuppresses() {
        // The generous bar, doing the job it was set loose for. A tool bucket's delta is measured per
        // CALL while the turn's is per TURN, so a turn making two calls to the tool that dominates it
        // shows half the coverage the tool is actually responsible for — demanding a full accounting
        // would mean the rule almost never fires.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 600, 1_800, CALL_SITE);

        Explanation explanation = MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction());

        assertNotNull(explanation);
        assertEquals(0.6, explanation.covered(), 0.01);
    }

    @Test
    @DisplayName("a tool that got FASTER does not explain a turn that got slower")
    void anOppositeMoveIsNotAnExplanation() {
        // Both fired, both are real, and they are two findings rather than one: a tool speeding up while
        // its caller slows down is if anything a stronger reason to report the turn, because whatever
        // absorbed the time is somewhere the tool grain cannot see.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 1_500, 500, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool shift measured over someone else's traffic explains nothing here")
    void aShiftInADifferentCallSiteIsNotAnExplanation() {
        // A tool bucket is keyed on its ActionSymbol and is deliberately NOT scoped per call site, so
        // matching has to be on the traffic the window was actually built from. Without this test the
        // rule would silence a real turn regression because an unrelated call site's tool slowed by a
        // similar amount in the same sweep.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 500, 2_500, OTHER_CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tool used by both call sites explains the one whose traffic it was measured over")
    void aSharedToolMatchesOnAnyContributingCallSite() {
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = new Shift(
                Measure.TOOL_DURATION,
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
        // Whoever reads the finding should be pointed at the tool that owns most of the added time, not
        // at whichever one this pass happened to fold first.
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
        // W₁ fires on the whole distribution, so a shift can live entirely in the tail while the median
        // holds. There is then no quantity of added time to apportion, and inventing one — by dividing
        // through a delta of zero — would hand back an infinity that clears every bar there is.
        Shift turn = turnShift(2_000, 2_000);
        Shift tool = toolShift("tool:search_docs", 400, 3_600, CALL_SITE);

        assertNull(MetricSuppression.explain(turn, List.of(tool), CONFIG.explainedByFraction()));
    }

    @Test
    @DisplayName("a tightened bar is what a swallowed regression is corrected with")
    void raisingTheFractionUnsuppresses() {
        // PLAN.md §11's response to "suppression hid a real turn regression" is to raise this number, so
        // it has to actually be the dial. Same pair of shifts, two bars, two answers.
        Shift turn = turnShift(2_000, 4_000);
        Shift tool = toolShift("tool:search_docs", 600, 1_800, CALL_SITE);

        assertNotNull(MetricSuppression.explain(turn, List.of(tool), 0.5));
        assertNull(MetricSuppression.explain(turn, List.of(tool), 0.75));
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    private static Shift turnShift(double refMillis, double curMillis) {
        return new Shift(
                Measure.TURN_DURATION,
                CALL_SITE,
                Set.of(CALL_SITE),
                fired(Measure.TURN_DURATION, curMillis >= refMillis ? Direction.UP : Direction.DOWN),
                refMillis,
                curMillis);
    }

    private static Shift toolShift(String bucketKey, double refMillis, double curMillis, String callSite) {
        return new Shift(
                Measure.TOOL_DURATION,
                bucketKey,
                Set.of(callSite),
                fired(Measure.TOOL_DURATION, curMillis >= refMillis ? Direction.UP : Direction.DOWN),
                refMillis,
                curMillis);
    }

    /**
     * A decision that fired. The rule reads none of its numbers — it decides in milliseconds, off the
     * medians beside it — so the ratio here is nominal; what matters is that a {@link Shift} is only ever
     * built from a comparison that actually crossed the floor.
     */
    private static Decision fired(String measure, Direction direction) {
        double w1 = direction == Direction.UP ? 0.34 : -0.34;
        return new Decision(true, measure, Reference.PINNED, w1, Math.exp(w1), direction, 500, 500, 0.139, null);
    }
}

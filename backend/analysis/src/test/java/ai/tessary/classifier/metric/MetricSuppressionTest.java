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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The §6.1 cross-grain rule, driven directly.
 *
 * <p>The first row of the first test matters most: suppression fails by swallowing a turn that got slower because the
 * agent did more, with every tool as fast as ever. No tool finding exists for it, and it is the reason turn duration
 * is measured at all. The bar ({@link MetricDriftConfig#DEFAULT_EXPLAINED_BY_FRACTION}) is loose, but it has a
 * bottom: 40ms of tool movement explains nothing of a 4s turn move.
 */
class MetricSuppressionTest {

    private static final MetricDriftConfig CONFIG = MetricDriftConfig.DEFAULTS;

    private static final String CALL_SITE = "discover-sales-prospects";

    /**
     * Each row: the turn median before and after, a tool's bucket, its median before and after, the call site whose
     * traffic it was measured over, and the bug a suppression there would be. None of them explains the turn.
     */
    @ParameterizedTest(name = "{6}")
    @CsvSource(delimiter = '|', textBlock = """
        2000 | 6000 | tool:lookup_id   | 20   | 60   | discover-sales-prospects | a 3x tool shift of 40ms swallows a 4s turn move: the floor is a ratio, the accounting absolute time
        2000 | 4000 | tool:search_docs | 1500 | 500  | discover-sales-prospects | a tool that got faster explains a turn that got slower, though the time went where the tool grain cannot see
        2000 | 4000 | tool:search_docs | 500  | 2500 | summarize-thread         | a tool measured over an unrelated call site silences a real turn regression
        2000 | 2000 | tool:search_docs | 400  | 3600 | discover-sales-prospects | a turn with a tail-only shift has no added median time, and dividing by that zero clears every bar
        """)
    void aToolShiftThatDoesNotAccountForTheTurnMoveExplainsNothing(
            double turnRef, double turnCur, String tool, double toolRef, double toolCur, String callSite, String bug) {
        Shift turn = turnShift(turnRef, turnCur);

        assertNull(
                MetricSuppression.explain(
                        turn, List.of(toolShift(tool, toolRef, toolCur, callSite)), CONFIG.explainedByFraction()),
                bug);
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

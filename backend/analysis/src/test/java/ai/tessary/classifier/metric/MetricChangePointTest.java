// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import ai.tessary.classifier.metric.MetricChangePoint.Located;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricEvidenceRefs.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Where a drift window's shift began. The onset is the step after the latest minimiser of a one-sided CUSUM of log
 * values against the reference mean. The bugs are an onset on a healthy row, an onset at the window close when every
 * row is already shifted, and an onset that moves between runs over the same rows.
 */
class MetricChangePointTest {

    private static final double LN_500 = Math.log(500);
    private static final double LN_1000 = Math.log(1000);
    private static final double LN_2000 = Math.log(2000);
    private static final double LN_8000 = Math.log(8000);

    private static Timed point(String traceId, String at, double value) {
        return new Timed(Ref.trace(traceId), at, value);
    }

    private static String second(int s) {
        return String.format(java.util.Locale.ROOT, "2026-06-01T00:00:%02dZ", s);
    }

    private static List<Timed> series(double... values) {
        List<Timed> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) out.add(point("t-" + i, second(i), values[i]));
        return out;
    }

    /**
     * Diffs against ln 2000 are 0, -0.693, 0, 0, +1.386, +1.386, so C is 0, 0, -0.693, -0.693, -0.693, +0.693. The
     * latest minimiser is k = 4: the first 8000 row. A first-minimiser rule gives 2, a healthy row.
     */
    @Test
    void locatesAStepInsideTheWindow() {
        List<Timed> window = series(LN_2000, LN_1000, LN_2000, LN_2000, LN_8000, LN_8000);

        assertEquals(new Located(4, second(4)), MetricChangePoint.locate(window, LN_2000, Direction.UP));
    }

    /**
     * A zero reading is ln 0 = -Infinity; clamped to the grid's floor (ln 1 ms = 0) it is one low row. Diffs against
     * ln 2000 are 0, -7.6, 0, 0, +1.386, +1.386, so the latest minimiser is k = 4, the first 8000 row. The bugs are
     * an unclamped row that pins every later CUSUM term at -Infinity, which moves the onset to the last row, and a
     * stored -Infinity that reads back as no value, which leaves the window with no located change point.
     */
    @Test
    void aZeroRowLocatesTheStepInMemoryAndAfterTheBlob() {
        MetricHistogram.Grid grid = MetricHistogram.Grid.duration();
        double[] values = {LN_2000, Math.log(0), LN_2000, LN_2000, LN_8000, LN_8000};
        List<Timed> window = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            window.add(Timed.measured(Ref.trace("t-" + i), second(i), values[i], grid));
        }

        assertEquals(new Located(4, second(4)), MetricChangePoint.locate(window, LN_2000, Direction.UP));
        assertEquals(
                new Located(4, second(4)),
                MetricChangePoint.locate(
                        MetricEvidenceRefs.fromJson(MetricEvidenceRefs.toJson(window)), LN_2000, Direction.UP));
    }

    /** Every row already shifted: the change point is the window's first row, not its close. */
    @Test
    void reportsWindowOpenWhenTheWholeWindowIsShifted() {
        assertEquals(
                new Located(0, second(0)),
                MetricChangePoint.locate(series(LN_8000, LN_8000, LN_8000, LN_8000, LN_8000), LN_2000, Direction.UP));
        assertEquals(
                new Located(0, second(0)),
                MetricChangePoint.locate(series(LN_500, LN_500, LN_500, LN_500, LN_500), LN_2000, Direction.DOWN));
    }

    /**
     * Two rows share an instant. Ordered by time then trace id, the rows are t-0, t-a, t-b in any input order; C is 0, 0,
     * 0, and the latest minimiser k = 2 is t-b, the shifted row.
     */
    @Test
    void tiesResolveTheSameWayEveryRun() {
        Timed first = point("t-0", "2026-06-01T00:00:00Z", LN_2000);
        Timed shifted = point("t-b", "2026-06-01T00:00:10Z", LN_8000);
        Timed healthy = point("t-a", "2026-06-01T00:00:10Z", LN_2000);

        for (List<Timed> input : List.of(List.of(first, shifted, healthy), List.of(healthy, shifted, first))) {
            List<Timed> ordered = MetricChangePoint.ordered(input);
            assertEquals(List.of(first, healthy, shifted), ordered);
            assertEquals(
                    new Located(2, "2026-06-01T00:00:10Z"), MetricChangePoint.locate(input, LN_2000, Direction.UP));
            assertEquals(List.of(Ref.trace("t-b")), MetricChangePoint.from(ordered, 2, 10));
        }
    }

    /**
     * Event times are ordered as instants, never as text: {@code Instant.toString} drops a zero fraction, so the
     * text "…:01Z" sorts after "…:01.500Z".
     */
    @Test
    void ordersByInstantNotByText() {
        Timed whole = point("t-1", "2026-06-01T00:00:01Z", LN_2000);
        Timed half = point("t-2", "2026-06-01T00:00:01.500Z", LN_2000);

        assertEquals(List.of(whole, half), MetricChangePoint.ordered(List.of(half, whole)));
    }

    /**
     * The reference mean of fifty identical rows is a float sum, a few ulps off the row value. Healthy rows must still
     * tie at zero, or the tiny positive diffs move the onset to the window's first row.
     */
    @Test
    void aRoundingGapBetweenTheReferenceAndAHealthyRowIsATie() {
        double sum = 0;
        for (int i = 0; i < 50; i++) sum += LN_2000;
        double referenceMean = sum / 50;

        List<Timed> window = series(LN_2000, LN_2000, LN_2000, LN_8000, LN_8000);

        assertEquals(new Located(3, second(3)), MetricChangePoint.locate(window, referenceMean, Direction.UP));
    }

    /** A window that spans this deploy has rows with no value: the method is unavailable, not a guess. */
    @Test
    void readsBlobsWithoutValues() {
        List<Timed> window = new ArrayList<>(series(LN_2000, LN_8000));
        window.add(Timed.of(Ref.trace("t-old")));

        assertNull(MetricChangePoint.locate(window, LN_2000, Direction.UP));
        assertNull(MetricChangePoint.locate(List.of(), LN_2000, Direction.UP));
    }

    /**
     * Rows from the change point on are the changepoint sample; the rows just before it are healthy and go to the
     * baseline side, so the onset row is never on the baseline side and no healthy row is citable.
     */
    @Test
    void beforeRowsAreBaselineNotChangepoint() {
        List<Timed> eight = series(new double[8]);
        assertEquals(refs(eight, 4, 8), MetricChangePoint.from(eight, 4, 10));
        assertEquals(refs(eight, 0, 4), MetricChangePoint.before(eight, 4, 10));
        assertEquals(List.of(), MetricChangePoint.before(eight, 0, 10));

        List<Timed> fifteen = series(new double[15]);
        assertEquals(refs(fifteen, 2, 12), MetricChangePoint.before(fifteen, 12, 10));
        assertEquals(refs(fifteen, 12, 15), MetricChangePoint.from(fifteen, 12, 10));
        assertEquals(refs(fifteen, 0, 10), MetricChangePoint.from(fifteen, 0, 10));
    }

    private static List<Ref> refs(List<Timed> ordered, int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> ordered.get(i).ref()).toList();
    }
}

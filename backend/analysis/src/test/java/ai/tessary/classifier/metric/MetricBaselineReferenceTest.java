// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.FindingRepository.ConfirmedSpan;
import ai.tessary.classifier.metric.MetricBaselineReference.Quantiles;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The drift reference a chart draws: pinned while it is on the live grid, else the rolling control. */
class MetricBaselineReferenceTest {

    private static final Grid LIVE = Grid.duration();
    private static final Grid DEAD = new Grid(LIVE.lo(), LIVE.ratio(), LIVE.bins() / 2);
    private static final String TODAY = "2026-10-08";

    @Test
    void quantiles_readThePinnedSketchOnTheLiveGrid() {
        MetricBaselineRow row = row(window(LIVE, 100, 1000).toJson(), control(LIVE, "2026-10-07", 100, 4000));

        Quantiles q =
                MetricBaselineReference.quantiles(row, LIVE, TODAY, Set.of()).orElseThrow();

        assertNear(1000, q.p50(), "the pin wins over the control");
        assertNear(1000, q.p95(), "every sample at one value");
    }

    @Test
    void driftSketchOnAnotherGrid_givesNoBaseline() {
        MetricBaselineRow row = row(window(DEAD, 100, 1000).toJson(), control(DEAD, "2026-10-07", 100, 4000));

        assertEquals(Optional.empty(), MetricBaselineReference.quantiles(row, LIVE, TODAY, Set.of()));
    }

    @Test
    void quantiles_fallBackToTheControlWhenThePinIsStale() {
        MetricBaselineRow row = row(window(DEAD, 100, 1000).toJson(), control(LIVE, "2026-10-07", 100, 4000));

        Quantiles q =
                MetricBaselineReference.quantiles(row, LIVE, TODAY, Set.of()).orElseThrow();

        assertNear(4000, q.p50(), "the control's level, not the dead pin's");
        assertEquals(
                Optional.empty(),
                MetricBaselineReference.quantiles(row, LIVE, TODAY, Set.of("2026-10-07")),
                "a day a confirmed regression ran through is not a reference");
    }

    @Test
    void excludedDays_coverEveryDayOfAConfirmedSpell() {
        assertEquals(
                Set.of("2026-10-05", "2026-10-06", "2026-10-07"),
                MetricBaselineReference.excludedDays(
                        List.of(new ConfirmedSpan("2026-10-05T23:00:00Z", "2026-10-07T01:00:00.5Z"))));
    }

    private static void assertNear(double expected, double actual, String message) {
        // A sketch quantile sits inside one log-spaced bin of the true value.
        assertTrue(Math.abs(actual / expected - 1) < LIVE.ratio() - 1, message + ": " + actual);
    }

    private static MetricHistogram window(Grid grid, int n, double value) {
        MetricHistogram h = new MetricHistogram(grid);
        for (int i = 0; i < n; i++) h.add(Math.log(value));
        return h;
    }

    private static String control(Grid grid, String day, int n, double value) {
        return MetricControl.empty()
                .fold(
                        grid,
                        day,
                        window(grid, n, value),
                        new MetricWorkload(MetricWorkload.grid(grid.bins())),
                        new MetricTokens(MetricTokens.grid(grid.bins())))
                .toJson();
    }

    private static MetricBaselineRow row(@Nullable String pinned, @Nullable String control) {
        return new MetricBaselineRow(
                "mb-1",
                "proj-1",
                "sig-1",
                MetricBaselineRow.Measure.TURN_DURATION,
                MetricBaselineRow.BucketKind.CALL_SITE,
                "cs-a",
                MetricBaselineRow.State.ARMED,
                pinned,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                control,
                null,
                0,
                null,
                null,
                null,
                "2026-10-01T00:00:00Z",
                "2026-10-01T00:00:00Z");
    }
}

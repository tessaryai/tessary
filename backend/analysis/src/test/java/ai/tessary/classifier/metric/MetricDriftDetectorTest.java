// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricDriftDetector.Silence;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metric-drift decision driven directly, without Spring, a database, or a clock.
 *
 * <p>The first three cases pin the operating point: 1.4× is the smallest move worth reporting, 1.02× is ordinary
 * traffic, and a bucket under the sample floor has not filled yet. Fixtures come from a seeded generator.
 */
class MetricDriftDetectorTest {

    /** The shipped operating point, so these tests move when the defaults do. */
    private static final MetricDriftConfig CONFIG = MetricDriftConfig.defaults();

    private static final int SAMPLES = 400;

    @Test
    @DisplayName("a 1.4x shift fires, and reports itself as 1.4x")
    void firesOnAFourtyPercentShift() {
        MetricSketch ref = durations(1.0);
        MetricSketch cur = durations(1.4);

        Decision d = MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PINNED, ref, cur, CONFIG);

        assertTrue(d.fired(), "0.34 in log units is comfortably past a floor of " + CONFIG.w1Floor());
        assertEquals(Direction.UP, d.direction(), "the current window sits above its reference");
        // The ratio is what a human reads ("1.4x slower"), so it must be the multiplicative shift. One bin is 5%
        // wide.
        assertEquals(1.4, d.ratio(), 0.05);
        assertEquals(Math.log(1.4), d.w1Log(), 0.05);
        assertEquals(SAMPLES, d.nRef());
        assertEquals(SAMPLES, d.nCur());
    }

    @Test
    @DisplayName("a 1.02x shift is silent: the floor is an effect size, not a significance test")
    void silentOnOrdinaryBreathing() {
        MetricSketch ref = durations(1.0);
        MetricSketch cur = durations(1.02);

        Decision d = MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PREVIOUS, ref, cur, CONFIG);

        // The case a KS test gets wrong: significance inflates with sample size, effect size does not (metric-
        // drift.md §4.2).
        assertFalse(d.fired());
        assertEquals(Silence.WITHIN_FLOOR, d.silence());
        double magnitude = Math.abs(d.w1Log());
        assertTrue(magnitude < CONFIG.w1Floor(), "measured " + magnitude + " against " + CONFIG.w1Floor());
    }

    @Test
    @DisplayName("the bar rises as the windows thin, and never falls below the configured move")
    void theBarScalesToTheSampleItIsMeasuredOn() {
        assertEquals(
                CONFIG.w1Floor(),
                MetricDriftDetector.effectiveFloor(CONFIG.windowTargetCount(), CONFIG.windowTargetCount(), CONFIG),
                1e-9);

        // A thin window is held to more: the scale is sqrt(target / harmonic mean), so 500 vs 100 needs a 27% move,
        // not 15%. Flat, it false-alarms one time in five.
        double thin = MetricDriftDetector.effectiveFloor(500, 100, CONFIG);
        assertEquals(CONFIG.w1Floor() * Math.sqrt(500.0 / (2.0 / (1.0 / 500 + 1.0 / 100))), thin, 1e-9);
        assertTrue(thin > CONFIG.w1Floor(), "a thin window must clear MORE, not less: " + thin);

        // Symmetric, or the same pair is judged by which side was pinned.
        assertEquals(thin, MetricDriftDetector.effectiveFloor(100, 500, CONFIG), 1e-9);

        // Never loosens, or the configured number stops meaning "the smallest move we report".
        assertEquals(CONFIG.w1Floor(), MetricDriftDetector.effectiveFloor(50_000, 50_000, CONFIG), 1e-9);
    }

    @Test
    @DisplayName("the implied false-alarm rate is reported from the traffic's spread, and decides nothing")
    void theImpliedRateExplainsTheMoveWithoutSettingIt() {
        MetricSketch wide = durations(1.0, 500, 0.80);
        MetricSketch tight = durations(1.0, 500, 0.42);

        double wideRate = MetricDriftDetector.impliedFalseAlarmRate(CONFIG.w1Floor(), wide.stdDevLog(), 500)
                .orElseThrow();
        double tightRate = MetricDriftDetector.impliedFalseAlarmRate(CONFIG.w1Floor(), tight.stdDevLog(), 500)
                .orElseThrow();

        // The same move on tighter traffic costs fewer false alarms.
        assertTrue(tightRate < wideRate / 2, "tight=" + tightRate + " wide=" + wideRate);

        // Round-trip the noise law's 1% bar, pinning the inversion rather than the fixture's spread.
        double barForOnePercent = 2.688 * wide.stdDevLog() * Math.sqrt(2.0 / 500);
        assertEquals(
                0.01,
                MetricDriftDetector.impliedFalseAlarmRate(barForOnePercent, wide.stdDevLog(), 500)
                        .orElseThrow(),
                1e-6);

        assertTrue(MetricDriftDetector.impliedFalseAlarmRate(CONFIG.w1Floor(), 0.0, 500)
                .isEmpty());
    }

    @Test
    @DisplayName("a window or reference under min_sample is silent whatever it shows — it waits, it is not skipped")
    void silentBelowTheSampleFloor() {
        MetricSketch ref = durations(1.0);
        // A doubling on 40 samples is not reported: the window is still filling, so the floor makes it wait (metric-
        // drift.md §2.3), not drop.
        MetricSketch thin = durations(2.0, 40);

        Decision d = MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PINNED, ref, thin, CONFIG);

        assertFalse(d.fired());
        assertEquals(Silence.BELOW_MIN_SAMPLE, d.silence());
        assertEquals(40, d.nCur());

        // A thin reference silences it too: 40 samples would report their own noise as a regression.
        Decision thinRef = MetricDriftDetector.decide(
                Measure.TURN_DURATION, Reference.PREVIOUS, durations(1.0, 40), durations(1.4), CONFIG);
        assertFalse(thinRef.fired());
        assertEquals(Silence.BELOW_MIN_SAMPLE, thinRef.silence());
    }

    @Test
    @DisplayName("faster fires exactly as loudly as slower")
    void fasterAlarmsAtTheSameBar() {
        Decision d = MetricDriftDetector.decide(
                Measure.TURN_DURATION, Reference.PINNED, durations(1.0), durations(1.0 / 1.4), CONFIG);

        // Downward shifts fire: the regression that reads as a win elsewhere is an agent that stopped verifying.
        assertTrue(d.fired());
        assertEquals(Direction.DOWN, d.direction());
        assertTrue(d.ratio() < 1.0, "a downward shift reports a ratio below one, not a negative one");
    }

    @Test
    @DisplayName("sketches on different grids are survived, never compared")
    void gridMismatchIsSurvivedRatherThanThrown() {
        MetricSketch onDurationGrid = durations(1.0);
        MetricSketch onCostGrid = new MetricHistogram(Grid.cost());
        for (int i = 0; i < SAMPLES; i++) {
            onCostGrid.add(Math.log(0.004));
        }

        Decision d =
                MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PINNED, onCostGrid, onDurationGrid, CONFIG);

        // Sketches on two grids (after a hist_bins edit) are refused as a silence, not an exception, since the next
        // close re-pins.
        assertFalse(d.fired());
        assertEquals(Silence.GRID_MISMATCH, d.silence());
    }

    @Test
    @DisplayName("a nonsense move is clamped before it ever reaches the detector")
    void aLiveEditedMoveCannotOpenTheGatesCompletely() {
        // A w1_floor of 0 would turn every window into a finding, so the clamp lives in the record's constructor.
        MetricDriftConfig edited = new MetricDriftConfig(
                CONFIG.measures(),
                CONFIG.windowTargetCount(),
                CONFIG.windowMaxHours(),
                CONFIG.minSample(),
                0.0,
                CONFIG.explainedByFraction(),
                CONFIG.settleSeconds(),
                CONFIG.histBins());

        assertTrue(edited.w1Floor() > 0.0, "clamped off zero");
        assertTrue(
                MetricDriftDetector.effectiveFloor(500, 500, edited) > 0.0,
                "and the bar it produces is a real one: " + MetricDriftDetector.effectiveFloor(500, 500, edited));
    }

    private static MetricSketch durations(double multiplier) {
        return durations(multiplier, SAMPLES);
    }

    private static MetricSketch durations(double multiplier, int samples) {
        return durations(multiplier, samples, 0.45);
    }

    /** The same, at a chosen log-space spread — what the derived floor reads to set its bar. */
    private static MetricSketch durations(double multiplier, int samples, double spread) {
        Random random = new Random(20260730L);
        MetricSketch sketch = new MetricHistogram(Grid.duration());
        for (int i = 0; i < samples; i++) {
            double millis = 2_000 * Math.exp(spread * random.nextGaussian());
            sketch.add(Math.log(millis * multiplier));
        }
        return sketch;
    }
}

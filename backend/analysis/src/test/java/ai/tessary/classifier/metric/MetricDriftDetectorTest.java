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
 * The metric-drift decision, driven directly — no Spring, no database, no clock. That is the point of
 * {@link MetricDriftDetector} existing separately from {@link MetricDriftSweep}: PLAN.md §9's eval
 * replays a real corpus through this class to set {@link MetricDriftConfig#w1Floor()}, and an eval that
 * had to stand up a schema first is an eval nobody runs often enough to tune with.
 *
 * <p>The three cases PLAN.md §4 names are the first three below, and between them they pin the whole
 * operating point: a 1.4× shift is the smallest move anyone would want reported, 1.02× is ordinary
 * traffic breathing, and a bucket under the sample floor is one whose window has not filled yet. Every
 * fixture is built from a seeded generator so the numbers are the same on every run — the same
 * reproducibility argument that put a fixed histogram behind the sketch rather than a t-digest.
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
        // The ratio is the sentence a human reads — "1.4x slower" — and it has to be the multiplicative
        // shift rather than something proportional to it, or the finding says the wrong number. One bin is
        // 5% wide, so the tolerance is the grid's resolution and not a fudge factor.
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

        // This is the case a KS test gets wrong. With 400 samples — never mind the 100k a busy call site
        // produces in a window — a two-percent shift is "significant", because significance inflates with
        // sample size while effect size does not. PROGRAM.md §4.2 names that as the single most common way
        // distribution monitoring fails in production.
        assertFalse(d.fired());
        assertEquals(Silence.WITHIN_FLOOR, d.silence());
        assertTrue(d.magnitude() < CONFIG.w1Floor(), "measured " + d.magnitude() + " against " + CONFIG.w1Floor());
    }

    @Test
    @DisplayName("the bar rises as the windows thin, and never falls below the configured move")
    void theBarScalesToTheSampleItIsMeasuredOn() {
        // Two windows of the configured target size are held to exactly the configured move.
        assertEquals(
                CONFIG.w1Floor(),
                MetricDriftDetector.effectiveFloor(CONFIG.windowTargetCount(), CONFIG.windowTargetCount(), CONFIG),
                1e-9);

        // A thin window is held to more, because the noise it is measured through is larger. The scale is
        // sqrt(target / harmonic mean) - at 500 against 100 the harmonic mean is 167, so the bar is
        // 0.139 * sqrt(3) ~ 0.241, a 27% move rather than a 15% one. Held flat instead, that comparison
        // false-alarms one time in five.
        double thin = MetricDriftDetector.effectiveFloor(500, 100, CONFIG);
        assertEquals(CONFIG.w1Floor() * Math.sqrt(500.0 / (2.0 / (1.0 / 500 + 1.0 / 100))), thin, 1e-9);
        assertTrue(thin > CONFIG.w1Floor(), "a thin window must clear MORE, not less: " + thin);

        // Symmetric in the two counts - which window is the thin one cannot change the bar, or the same
        // pair would be judged differently depending on which was pinned.
        assertEquals(thin, MetricDriftDetector.effectiveFloor(100, 500, CONFIG), 1e-9);

        // And it never loosens. A bucket thicker than the target could support a smaller bar, but then
        // the configured number would stop meaning "the smallest move we will report".
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

        // The same move on tighter traffic is a stricter one, so it costs fewer false alarms. That
        // variation is the price of the MOVE being the promise, and showing it is the whole point of
        // this number existing.
        assertTrue(tightRate < wideRate / 2, "tight=" + tightRate + " wide=" + wideRate);

        // Round-trip: build the bar that the noise law says produces a 1% rate on this traffic, feed it
        // back, and get 1% out. That pins the inversion itself rather than the fixture's exact spread.
        double barForOnePercent = 2.688 * wide.stdDevLog() * Math.sqrt(2.0 / 500);
        assertEquals(
                0.01,
                MetricDriftDetector.impliedFalseAlarmRate(barForOnePercent, wide.stdDevLog(), 500)
                        .orElseThrow(),
                1e-6);

        // Unmeasurable spread reports nothing rather than a number nobody can stand behind.
        assertTrue(MetricDriftDetector.impliedFalseAlarmRate(CONFIG.w1Floor(), 0.0, 500)
                .isEmpty());
    }

    @Test
    @DisplayName("a window under min_sample is silent whatever it shows — it waits, it is not skipped")
    void silentBelowTheSampleFloor() {
        MetricSketch ref = durations(1.0);
        // A doubling. Enormous, unmistakable, and reported by nobody: 40 samples cannot tell a real move
        // from four unlucky traces, and a bucket this thin is one whose window is still filling. The floor
        // makes it WAIT (PROGRAM.md §2.3) rather than be dropped as too rare to watch — a tool called
        // thirty times a week gets watched on a slower clock, not never.
        MetricSketch thin = durations(2.0, 40);

        Decision d = MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PINNED, ref, thin, CONFIG);

        assertFalse(d.fired());
        assertEquals(Silence.BELOW_MIN_SAMPLE, d.silence());
        assertEquals(40, d.nCur());
    }

    @Test
    @DisplayName("a thin REFERENCE silences the comparison too")
    void silentWhenTheReferenceIsThin() {
        // The reference is the bar. A bar built from 40 samples of a quiet week would report that week's
        // sampling noise as a regression in the busy one that follows.
        Decision d = MetricDriftDetector.decide(
                Measure.TURN_DURATION, Reference.PREVIOUS, durations(1.0, 40), durations(1.4), CONFIG);

        assertFalse(d.fired());
        assertEquals(Silence.BELOW_MIN_SAMPLE, d.silence());
    }

    @Test
    @DisplayName("faster fires exactly as loudly as slower")
    void fasterAlarmsAtTheSameBar() {
        Decision d = MetricDriftDetector.decide(
                Measure.TURN_DURATION, Reference.PINNED, durations(1.0), durations(1.0 / 1.4), CONFIG);

        // The price of this is a trickle of "yes, we optimized that" dismissals. What it buys is the one
        // regression that reads as a win on every other dashboard in the product: an agent that quietly
        // stopped doing its verification step.
        assertTrue(d.fired());
        assertEquals(Direction.DOWN, d.direction());
        assertTrue(d.ratio() < 1.0, "a downward shift reports a ratio below one, not a negative one");
    }

    @Test
    @DisplayName("no reference yet is its own silence, not a firing and not a zero")
    void noReferenceIsNamed() {
        Decision d = MetricDriftDetector.decide(Measure.TURN_DURATION, Reference.PINNED, null, durations(1.0), CONFIG);

        assertFalse(d.fired());
        assertEquals(Silence.NO_REFERENCE, d.silence());
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

        // Reached by editing hist_bins on a project whose sketches predate the edit. A W₁ across two grids
        // would be a plausible number nothing downstream would question, so it is refused — but refused as
        // a silence rather than an exception, because a config edit is an event the sweep survives (its
        // next close re-pins) and not a reason to dead-letter it.
        assertFalse(d.fired());
        assertEquals(Silence.GRID_MISMATCH, d.silence());
    }

    @Test
    @DisplayName("a nonsense move is clamped before it ever reaches the detector")
    void aLiveEditedMoveCannotOpenTheGatesCompletely() {
        // The config blob is editable per project while the classifier is being tuned. A w1_floor of 0
        // turns every closed window into a finding on the very next sweep, with nothing in the code path
        // to notice — so the clamp lives in the record's constructor, not at the call site.
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

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

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

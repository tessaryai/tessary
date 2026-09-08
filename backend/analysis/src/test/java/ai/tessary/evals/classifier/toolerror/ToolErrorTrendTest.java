// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.evals.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.evals.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.evals.classifier.toolerror.ToolErrorTrend.Sweep;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The replay. {@code classifiers/tool_error/PROGRAM.md} §5.
 *
 * <p>These are the behaviours that only exist over a time series, and so cannot be seen in
 * {@code ToolErrorDetectorTest}: which traffic becomes the reference, whether a recovered tool still
 * counts as firing, and whether a sweep that carries state forward can disagree with one that does not.
 */
class ToolErrorTrendTest {

    private static final ToolErrorConfig CONFIG = ToolErrorConfig.defaults();
    private static final String TOOL = "tool:search_docs";
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");

    /** {@code hours} buckets of {@code callsPerHour} calls, failing at {@code rate}. Deterministic. */
    private static List<HourlyToolTally> series(int hours, int callsPerHour, double rate) {
        return series(new ArrayList<>(), 0, hours, callsPerHour, rate);
    }

    private static List<HourlyToolTally> series(
            List<HourlyToolTally> into, int fromHour, int hours, int callsPerHour, double rate) {
        for (int h = 0; h < hours; h++) {
            long failures = Math.round(callsPerHour * rate);
            into.add(new HourlyToolTally(
                    START.plus(Duration.ofHours(fromHour + h)).toString(), TOOL, callsPerHour, failures));
        }
        return into;
    }

    /** A sweep with nothing carried — the rebuild path, which is what most of these exercise. */
    private static List<Spell> spells(List<HourlyToolTally> tallies) {
        return ToolErrorTrend.sweep(tallies, CONFIG, Map.of(), Map.of()).spells();
    }

    private static Map<String, CarriedState> carriedFrom(Sweep sweep) {
        return sweep.advanced().stream().collect(Collectors.toMap(CarriedState::toolKey, c -> c));
    }

    /** The reference a sweep settled on. Non-null by the time anything is judged; asserted, not assumed. */
    private static ToolErrorRate requireBaseline(Sweep sweep) {
        ToolErrorRate baseline = sweep.advanced().get(0).baseline();
        assertNotNull(baseline, "a judged tool must carry the reference it was judged against");
        return baseline;
    }

    // ---------------------------------------------------------------------------------------------
    // The live-set contract
    // ---------------------------------------------------------------------------------------------

    @Test
    void aToolThatDegradedAndStayedDegradedIsFiring() {
        List<HourlyToolTally> s = series(20, 200, 0.01); // 4,000 calls of reference at 1%
        series(s, 20, 30, 200, 0.05); // then 6,000 calls at 5%

        List<Spell> spells = spells(s);
        assertEquals(1, spells.size());
        assertEquals(TOOL, spells.get(0).toolKey());
        assertEquals(Direction.UP, spells.get(0).decision().direction());
    }

    /**
     * <b>The contract this classifier used to have, inverted deliberately.</b>
     *
     * <p>A tool that broke on Tuesday and was fixed on Wednesday used to drop out of the live set by
     * Friday: the accumulator was capped at three times the threshold, so a day of clean traffic drained
     * it and the case closed itself. That cap is gone, because it also pinned every serious outage to the
     * same ceiling and made criticality a flat tie across every real problem.
     *
     * <p>So a fixed tool now keeps firing until a human says otherwise, and that is the intended shape:
     * a big crash is worth showing even after it is over. {@code CaseService.resolve} clears the
     * accumulator on close, which is the only thing that ends the spell — see the next test.
     */
    @Test
    void aToolThatRecoveredKeepsFiringUntilSomebodyClosesIt() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 20, 200, 0.05); // breaks
        assertEquals(1, spells(s).size(), "still broken, so still firing");

        series(s, 40, 60, 200, 0.01); // and is fixed, with days of clean traffic
        assertEquals(1, spells(s).size(), "a fixed tool stays on the board until a human closes the case");
    }

    /** And clearing the accumulator, which is what closing a case does, is what ends it. */
    @Test
    void clearingTheAccumulatorDropsAToolOutOfTheLiveSet() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 20, 200, 0.05);
        Sweep broken = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());
        assertEquals(1, broken.spells().size());

        // What ToolErrorStateRepository.reset leaves behind: zeroed arms, no onset, watermark kept.
        CarriedState was = broken.advanced().get(0);
        Map<String, CarriedState> cleared = Map.of(
                TOOL,
                new CarriedState(
                        TOOL,
                        ToolErrorDetector.State.EMPTY,
                        was.baseline(),
                        was.watermarkBucket(),
                        was.stateEpoch(),
                        null,
                        null));

        series(s, 40, 60, 200, 0.01); // the tool is genuinely fixed
        assertTrue(
                ToolErrorTrend.sweep(s, CONFIG, Map.of(), cleared).spells().isEmpty(),
                "cleared evidence plus a healthy tool must not re-raise the same spell");
    }

    @Test
    void aQuietToolWaitsRatherThanBeingJudged() {
        // 100 calls total, well under minBaselineCalls. Not enough to have a reference at all.
        assertTrue(spells(series(10, 10, 0.20)).isEmpty());
    }

    @Test
    void aHealthyToolIsSilentHoweverLongItRuns() {
        assertTrue(spells(series(400, 200, 0.01)).isEmpty(), "80,000 in-control calls");
    }

    // ---------------------------------------------------------------------------------------------
    // Carrying state forward — the bug class migration 0070 takes back on purpose
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>The property everything else here rests on.</b> Sweeping in pieces and sweeping in one go must
     * agree, or the answer a partner sees depends on how often the sweep happened to run.
     */
    @Test
    void anIncrementalSweepAgreesWithAFullReplay() {
        List<HourlyToolTally> all = series(20, 200, 0.01);
        series(all, 20, 30, 200, 0.05);

        Spell wholeThing =
                ToolErrorTrend.sweep(all, CONFIG, Map.of(), Map.of()).spells().get(0);

        // The same data, delivered over three passes, each seeing everything so far.
        Map<String, CarriedState> carried = new HashMap<>();
        Sweep last = ToolErrorTrend.sweep(all.subList(0, 30), CONFIG, Map.of(), carried);
        for (int upTo : List.of(40, 50)) {
            carried = carriedFrom(last);
            last = ToolErrorTrend.sweep(all.subList(0, upTo), CONFIG, Map.of(), carried);
        }

        Spell piecemeal = last.spells().get(0);
        assertEquals(wholeThing.decision().statistic(), piecemeal.decision().statistic(), 1e-9);
        assertEquals(
                wholeThing.decision().callsSinceOnset(), piecemeal.decision().callsSinceOnset());
        assertEquals(wholeThing.onsetBucket(), piecemeal.onsetBucket(), "a drifting onset reopens closed cases");
    }

    /**
     * The watermark earning its keep. A sweep that re-reads buckets it already folded would count the same
     * failures twice and manufacture a case out of nothing — the exact failure the recompute-on-read
     * design used to be immune to by construction.
     */
    @Test
    void resweepingTheSameBucketsChangesNothing() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);

        Sweep first = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());
        Sweep again = ToolErrorTrend.sweep(s, CONFIG, Map.of(), carriedFrom(first));

        assertEquals(
                first.spells().get(0).decision().statistic(),
                again.spells().get(0).decision().statistic(),
                1e-9,
                "the same hours folded twice must not add evidence twice");
        assertEquals(
                0,
                again.advanced().get(0).state().callsSinceOnsetUp()
                        - first.advanced().get(0).state().callsSinceOnsetUp());
    }

    /**
     * Evidence scored under one set of log-likelihood weights means nothing under another, and the failure
     * is silent — no error, just a number that is quietly wrong. So a tuning change must rebuild rather
     * than resume.
     */
    @Test
    void aTuningChangeRebuildsRatherThanResuming() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);
        Sweep first = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());

        ToolErrorConfig retuned = new ToolErrorConfig(250_000L, 3.0, 0.005, 0.05, 500, 0.01, 300, 8);
        Sweep after = ToolErrorTrend.sweep(s, retuned, Map.of(), carriedFrom(first));
        Sweep fromScratch = ToolErrorTrend.sweep(s, retuned, Map.of(), Map.of());
        assertEquals(
                fromScratch.advanced().get(0).state().sUp(),
                after.advanced().get(0).state().sUp(),
                1e-9,
                "state built under the old weights must be discarded, not carried");
    }

    /**
     * And the numbers must be the tool's, not the replay's. A count that grew with how often the replay
     * ran would be the recompute equivalent of the incremented counter §5.1 forbids.
     */
    @Test
    void theReportedCountsAreTheToolsNotTheReplays() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);

        Spell spell = spells(s).get(0);
        // The reference closes as soon as it is thick enough (600 calls, three 200-call buckets), and
        // everything after it is the observation. 10,000 calls in total, counted once each.
        assertEquals(600, spell.baseline().calls(), "the reference is the leading traffic, once");
        assertEquals(9400, spell.observed().calls(), "and the observation is the rest of it, once");
    }

    // ---------------------------------------------------------------------------------------------
    // The reference is frozen, which is what lets a slow bleed be seen at all
    // ---------------------------------------------------------------------------------------------

    /**
     * The failure a rolling baseline cannot see, and the reason {@code CusumDetector} replaced the old
     * pooled-window gate: if the reference moves with the traffic, a rate that creeps up week over week
     * looks normal at every step and no alarm is ever raised.
     */
    @Test
    void aSlowRampIsCaughtBecauseTheReferenceDoesNotMoveWithIt() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        double rate = 0.01;
        for (int h = 0; h < 60; h++) {
            rate += 0.0008; // 1% creeping to ~5% over sixty hours, never jumping
            series(s, 20 + h, 1, 200, rate);
        }
        assertEquals(1, spells(s).size(), "a slow bleed must not be normalized away");
    }

    @Test
    void theReferenceIsTheLeadingTrafficAndStopsAtTheMinimum() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);
        Spell spell = spells(s).get(0);
        // minBaselineCalls is 500, and buckets are 200 calls, so the reference closes on the third bucket
        // rather than consuming the whole quiet stretch.
        assertEquals(600, spell.baseline().calls());
        assertTrue(spell.baseline().rate() < 0.02, "and it is the healthy rate, not the degraded one");
    }

    /**
     * A reference learned once and kept, which §5 always claimed and the recompute never delivered: it was
     * re-learned each pass from the leading buckets of a window that slides forward every hour, so it
     * walked after the very degradation it was meant to measure.
     */
    @Test
    void aLearnedReferenceIsKeptRatherThanRelearnedAsTheWindowSlides() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);
        Sweep first = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());
        ToolErrorRate learned = requireBaseline(first);
        assertEquals(600, learned.calls());

        // The window has slid: the healthy hours that taught the reference have aged out entirely.
        List<HourlyToolTally> slid = new ArrayList<>(s.subList(25, s.size()));
        Sweep later = ToolErrorTrend.sweep(slid, CONFIG, Map.of(), carriedFrom(first));

        assertEquals(
                learned.calls(),
                requireBaseline(later).calls(),
                "the reference must not re-learn itself off degraded traffic");
        assertEquals(1, later.spells().size(), "and the tool is still visibly broken against it");
    }

    // ---------------------------------------------------------------------------------------------
    // Onset
    // ---------------------------------------------------------------------------------------------

    @Test
    void onsetLandsInsideTheDegradedStretchNotAtTheAlarm() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);

        Spell spell = spells(s).get(0);
        assertNotNull(spell.onsetBucket());
        Instant onset = Instant.parse(spell.onsetBucket());
        Instant broke = START.plus(Duration.ofHours(20));
        Instant end = START.plus(Duration.ofHours(50));
        assertTrue(!onset.isBefore(broke), "onset " + onset + " predates the break at " + broke);
        assertTrue(onset.isBefore(end), "onset should be where it turned, not where the replay ended");
    }

    // ---------------------------------------------------------------------------------------------
    // Several tools at once
    // ---------------------------------------------------------------------------------------------

    @Test
    void toolsAreJudgedIndependently() {
        List<HourlyToolTally> s = new ArrayList<>();
        for (int h = 0; h < 50; h++) {
            String at = START.plus(Duration.ofHours(h)).toString();
            s.add(new HourlyToolTally(at, "tool:healthy", 200, 2));
            s.add(new HourlyToolTally(at, "tool:broken", 200, h < 20 ? 2 : 10));
        }
        List<Spell> spells = spells(s);
        assertEquals(1, spells.size(), "only the broken one");
        assertEquals("tool:broken", spells.get(0).toolKey());
    }

    @Test
    void aToolWithNoTrafficAtAllProducesNothingRatherThanDividingByZero() {
        assertTrue(spells(List.of()).isEmpty());
        assertNull(ToolErrorTrend.replay(TOOL, List.of(), CONFIG, null, null));
    }
}

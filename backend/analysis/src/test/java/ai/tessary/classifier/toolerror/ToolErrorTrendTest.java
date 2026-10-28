// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Sweep;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The replay (tool-error.md §5): behaviours that exist only over a time series, which {@code ToolErrorDetectorTest}
 * cannot see.
 */
class ToolErrorTrendTest {

    private static final ToolErrorConfig CONFIG = ToolErrorConfig.defaults();
    private static final String TOOL = "tool:search_docs";
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");

    private static String hour(int h) {
        return START.plus(Duration.ofHours(h)).toString();
    }

    private static ToolErrorRate rate(long calls, long failures) {
        ToolErrorRate r = new ToolErrorRate();
        r.addCounts(calls, failures);
        return r;
    }

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

    /** A sweep with nothing carried: the rebuild path. */
    private static List<Spell> spells(List<HourlyToolTally> tallies) {
        return ToolErrorTrend.sweep(tallies, CONFIG, Map.of(), Map.of()).spells();
    }

    private static Map<String, CarriedState> carriedFrom(Sweep sweep) {
        return sweep.advanced().stream().collect(Collectors.toMap(CarriedState::toolKey, c -> c));
    }

    private static double rateOf(ToolErrorRate window) {
        return (double) window.failures() / window.calls();
    }

    private static ToolErrorRate requireBaseline(Sweep sweep) {
        ToolErrorRate baseline = sweep.advanced().get(0).baseline();
        assertNotNull(baseline, "a judged tool must carry the reference it was judged against");
        return baseline;
    }

    /** Clearing the accumulator, as closing a case does, is what ends it. */
    @Test
    void clearingTheAccumulatorDropsAToolOutOfTheLiveSet() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 20, 200, 0.05);
        Sweep broken = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());
        assertEquals(1, broken.spells().size());

        // What ToolErrorStateRepository.reset leaves: zeroed arms, no onset, watermark kept.
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
                        null,
                        null));

        series(s, 40, 60, 200, 0.01); // the tool is genuinely fixed
        assertTrue(
                ToolErrorTrend.sweep(s, CONFIG, Map.of(), cleared).spells().isEmpty(),
                "cleared evidence plus a healthy tool must not re-raise the same spell");
    }

    /** Re-reading folded buckets would count failures twice and manufacture a case (migration 0070's bug class). */
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
     * Evidence under one set of log-likelihood weights is silently wrong under another, so a tuning change rebuilds.
     */
    @Test
    void aTuningChangeRebuildsRatherThanResuming() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);
        Sweep first = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());

        ToolErrorConfig retuned = new ToolErrorConfig(250_000L, 3.0, 0.005, 500, 0.01, 8);
        Sweep after = ToolErrorTrend.sweep(s, retuned, Map.of(), carriedFrom(first));
        Sweep fromScratch = ToolErrorTrend.sweep(s, retuned, Map.of(), Map.of());
        assertEquals(
                fromScratch.advanced().get(0).state().sUp(),
                after.advanced().get(0).state().sUp(),
                1e-9,
                "state built under the old weights must be discarded, not carried");
    }

    /**
     * Why {@code CusumDetector} replaced the pooled-window gate: a reference that moves with traffic never sees a
     * slow creep.
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

    /** Judges from 500 calls, as tool_error does, and keeps learning the reference until 2,000. */
    private static final ToolErrorConfig LEARNING = withFreeze(CONFIG, 2000);

    private static ToolErrorConfig withFreeze(ToolErrorConfig c, int freeze) {
        return new ToolErrorConfig(
                c.arlTarget(),
                c.shiftMultiple(),
                c.shiftFloor(),
                c.minBaselineCalls(),
                c.downArmMinRate(),
                c.maxPatterns(),
                c.minDecisionInterval(),
                freeze);
    }

    @Test
    void aRiseDuringLearningIsStillCaught() {
        List<HourlyToolTally> s = series(4, 200, 0.01); // 800 calls: judging has started, learning has not ended
        series(s, 4, 30, 200, 0.05);

        List<Spell> spells =
                ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of()).spells();
        assertEquals(1, spells.size(), "a rise that lands while the reference is still learning must not be absorbed");
        assertEquals(Direction.UP, spells.get(0).decision().direction());
        Instant onset = Instant.parse(spells.get(0).onsetBucket());
        assertTrue(!onset.isBefore(START.plus(Duration.ofHours(4))), "and its onset is where it rose");
    }

    /**
     * A rebuilding caller keeps its still-learning reference and learns only newer settled hours; learning every hour
     * would count some twice.
     */
    @Test
    void aRebuildKeepsALearningReferenceAndLearnsOnlyTheNewHours() {
        List<HourlyToolTally> s = series(7, 200, 0.01); // 1,400 calls; hours 5 and 6 have not settled
        Sweep first = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(1000, requireBaseline(first).calls());

        Sweep rebuilt = ToolErrorTrend.sweep(
                s, LEARNING, Map.of(), Map.of(TOOL, first.advanced().get(0).rebuilding()));
        assertEquals(1000, requireBaseline(rebuilt).calls(), "the same hours, learned once");

        series(s, 7, 2, 200, 0.01);
        Sweep grown = ToolErrorTrend.sweep(
                s, LEARNING, Map.of(), Map.of(TOOL, rebuilt.advanced().get(0).rebuilding()));
        assertEquals(1400, requireBaseline(grown).calls(), "and then only the hours that settled after them");
    }

    @Test
    void aResetThatKeepsALearningReferenceKeepsWhatItLearned() {
        List<HourlyToolTally> s = series(7, 200, 0.01); // 1,000 calls learned through hour 4, still learning
        CarriedState was =
                ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of()).advanced().get(0);

        // Reset keeps the reference, watermark and learned_through, clears the arm, and fences at hour 8.
        String resetAt = START.plus(Duration.ofHours(8)).toString();
        CarriedState reset = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                was.baseline(),
                was.watermarkBucket(),
                was.stateEpoch(),
                null,
                null,
                resetAt,
                was.learnedThrough(),
                null);

        // Hours 5-7 are before the fence; 8 and 9 after it and settled; 10 and 11 not settled.
        series(s, 7, 5, 200, 0.10);
        Sweep rebuilt = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of(TOOL, reset.rebuilding()));
        ToolErrorRate reference = requireBaseline(rebuilt);
        assertEquals(1400, reference.calls(), "the hours it held, then the settled hours after the fence, none before");
        assertEquals(10 + 40, reference.failures());
        assertEquals(resetAt, rebuilt.advanced().get(0).resetAt());
    }

    @Test
    void aResetRelearnsFromTheFenceUpToTheFreezeAgain() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        Sweep learned = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(2000, requireBaseline(learned).calls());

        // resetAndRelearn leaves no reference, no watermark, and a fence at the press.
        String resetAt = START.plus(Duration.ofHours(20)).toString();
        CarriedState relearning = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                learned.advanced().get(0).stateEpoch(),
                null,
                null,
                resetAt);

        series(s, 20, 5, 200, 0.05); // 1,000 calls after the reset, at the new normal
        Sweep partway = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of(TOOL, relearning));
        assertEquals(
                600,
                requireBaseline(partway).calls(),
                "learning again from the fence, not before it, and only settled");
        assertTrue(rateOf(requireBaseline(partway)) > 0.04);
        assertTrue(partway.spells().isEmpty(), "and the new normal is not judged against the old one");

        series(s, 25, 20, 200, 0.05);
        Sweep resumed = ToolErrorTrend.sweep(s, LEARNING, Map.of(), carriedFrom(partway));
        assertEquals(2000, requireBaseline(resumed).calls(), "up to the freeze again, and no further");
        assertEquals(resetAt, resumed.advanced().get(0).resetAt());

        Map<String, CarriedState> rebuilding =
                Map.of(TOOL, partway.advanced().get(0).rebuilding());
        Sweep rebuilt = ToolErrorTrend.sweep(s, LEARNING, Map.of(), rebuilding);
        assertEquals(2000, requireBaseline(rebuilt).calls(), "a rebuild relearns from the fence too");
        assertTrue(rateOf(requireBaseline(rebuilt)) > 0.04);
    }

    /**
     * Every hour is judged, but an hour whose labels are still arriving must not join the reference the moment it is
     * judged. With a one-hour delay, the newest two hours on the key's own clock have not settled.
     */
    @Test
    void replay_judgesAnUnsettledHourWithoutLearningIt() {
        Sweep first = ToolErrorTrend.sweep(series(7, 200, 0.01), LEARNING, Map.of(), Map.of());

        CarriedState carried = first.advanced().get(0);
        assertEquals(hour(6), carried.watermarkBucket(), "every hour is judged");
        assertEquals(1000, requireBaseline(first).calls(), "hours 0-4 have settled; 5 and 6 have not");
        assertEquals(hour(4), carried.learnedThrough());
        assertTrue(first.spells().isEmpty());
    }

    /** A resumed key must learn, on a later pass, the hours that were unsettled when it judged them. */
    @Test
    void replay_learnsAnHourOnceItSettlesOnALaterPass() {
        List<HourlyToolTally> s = series(7, 200, 0.01);
        Sweep first = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());

        series(s, 7, 2, 200, 0.01);
        Sweep later = ToolErrorTrend.sweep(s, LEARNING, Map.of(), carriedFrom(first));

        CarriedState carried = later.advanced().get(0);
        assertEquals(1400, requireBaseline(later).calls(), "hours 5 and 6 settle once hours 7 and 8 exist");
        assertEquals(hour(6), carried.learnedThrough());
        assertEquals(hour(8), carried.watermarkBucket());
    }

    /**
     * A rebuild re-reads every hour; the hours between {@code learned_through} and the watermark must be learned once,
     * not on every pass, and not lost.
     */
    @Test
    void replay_neverLearnsTheSameHourTwiceAcrossRebuilds() {
        List<HourlyToolTally> s = series(9, 200, 0.01);
        CarriedState stored = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                rate(1000, 10),
                hour(6),
                CarriedState.epochOf(LEARNING, ToolErrorTrend.STATE_SCHEMA_VERSION),
                null,
                null,
                null,
                hour(4),
                null);

        Sweep once = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of(TOOL, stored.rebuilding()));
        assertEquals(1400, requireBaseline(once).calls(), "hours 5 and 6, learned once");

        Sweep twice = ToolErrorTrend.sweep(
                s, LEARNING, Map.of(), Map.of(TOOL, once.advanced().get(0).rebuilding()));
        assertEquals(1400, requireBaseline(twice).calls(), "and not again");
    }

    /** Under an open finding, a real rise must be judged but never learned as normal. */
    @Test
    void replay_relearnsOnlyBeforeAnOpenFindingsOnset() {
        ToolErrorConfig config = withFreeze(CONFIG, 5000);
        CarriedState fenced = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                CarriedState.epochOf(config, ToolErrorTrend.STATE_SCHEMA_VERSION),
                null,
                null,
                null,
                null,
                hour(10));
        List<HourlyToolTally> s = series(10, 200, 0.01);
        series(s, 10, 20, 200, 0.10);

        Sweep sweep = ToolErrorTrend.sweep(s, config, Map.of(), Map.of(TOOL, fenced));

        ToolErrorRate reference = requireBaseline(sweep);
        assertEquals(2000, reference.calls(), "only the hours before the onset");
        assertEquals(20, reference.failures());
        assertEquals(hour(9), sweep.advanced().get(0).learnedThrough());
        assertEquals(1, sweep.spells().size());
        assertEquals(Direction.UP, sweep.spells().get(0).decision().direction());
        assertEquals(hour(10), sweep.spells().get(0).onsetBucket());
    }

    /** A reference built from unsettled hours would judge the next hour against labels that are still arriving. */
    @Test
    void replay_waitsWhileTheLeadingHoursAreUnsettled() {
        Sweep sweep = ToolErrorTrend.sweep(series(3, 200, 0.01), CONFIG, Map.of(), Map.of());

        assertTrue(sweep.advanced().isEmpty(), "600 calls, but only hour 0 has settled");
        assertTrue(sweep.spells().isEmpty());
    }
}

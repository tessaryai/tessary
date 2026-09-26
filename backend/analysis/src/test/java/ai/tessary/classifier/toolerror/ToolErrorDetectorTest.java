// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Silence;
import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolFailure.Recognized;
import ai.tessary.classifier.toolerror.ToolFailure.Source;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The statistic. {@code devdocs/concepts/tool-error.md} §4. */
class ToolErrorDetectorTest {

    private static final ToolErrorConfig CONFIG = ToolErrorConfig.defaults();
    private static final Recognized BOOM = new Recognized(Source.SPAN_STATUS, "boom");

    private static ToolErrorRate pinned(long calls, long failures) {
        ToolErrorRate r = new ToolErrorRate();
        for (long i = 0; i < failures; i++) r.add(BOOM);
        for (long i = failures; i < calls; i++) r.add(null);
        return r;
    }

    private static State step(State s, ToolErrorRate ref, ToolErrorConfig config, boolean failed, String at) {
        return ToolErrorDetector.advanceBucket(s, ref, config, 1, failed ? 1 : 0, at);
    }

    /** Feed {@code n} calls at rate {@code p}; returns where the run alarmed, or -1. */
    private static long callsToAlarm(ToolErrorRate ref, double p, long n, Random rng) {
        State s = State.EMPTY;
        for (long i = 1; i <= n; i++) {
            s = step(s, ref, CONFIG, rng.nextDouble() < p, "2026-08-08T00:00:00Z");
            if (ToolErrorDetector.decide(s, ref, CONFIG).fired()) return i;
        }
        return -1;
    }

    /**
     * {@code DEFAULT_ARL_TARGET} claims a false alarm about every 250,000 calls, a number from an external Monte
     * Carlo, asserted here loosely enough not to flake but tightly enough to catch a scoring bug that makes the run
     * length hundreds. Fixed seed.
     */
    @Test
    void inControlTrafficRunsAVeryLongTimeBeforeAFalseAlarm() {
        ToolErrorRate ref = pinned(20_000, 200); // 1% in control
        Random rng = new Random(20260808L);
        int alarms = 0;
        int runs = 12;
        for (int i = 0; i < runs; i++) {
            if (callsToAlarm(ref, 0.01, 40_000, rng) > 0) alarms++;
        }
        // 480k in-control calls at an ARL0 of ~250k expect about two; four or more means a run length near 100k.
        assertTrue(alarms <= 4, alarms + " false alarms in 480k in-control calls — h is not buying its ARL");
    }

    /** The other half: a real regression still trips it. */
    @Test
    void aSustainedDoublingIsCaughtWithinAFewThousandCalls() {
        ToolErrorRate ref = pinned(20_000, 200); // 1% in control
        long at = callsToAlarm(ref, 0.02, 40_000, new Random(11L));
        assertTrue(at > 0, "a sustained doubling never alarmed");
        assertTrue(at < 10_000, "took " + at + " calls to notice a doubling");
    }

    /**
     * Why {@code shiftFloor} exists: twice nearly-zero is still nearly-zero, so the tool whose failing matters most
     * would be undetectable.
     */
    @Test
    void aToolThatNeverFailedAndStartsFailingIsCaught() {
        ToolErrorRate ref = pinned(20_000, 0);
        long at = callsToAlarm(ref, 0.01, 40_000, new Random(7L));
        assertTrue(at > 0, "a clean tool starting to fail 1 in 100 never alarmed");
        assertTrue(at < 5_000, "took " + at + " calls");
    }

    /**
     * A sustained 20.0% to 21.1% is a real change and nobody's problem. The old effect-size gate that declined it
     * measured against the tool's whole history, so it also silenced every real outage, and is gone.
     *
     * <p>The derived threshold (h about 9.9, where a flat 6.0 gave a run length 47x too short) now puts 21.1% below
     * the accumulator's break-even. Tolerated, not impossible: this asserts the separation, not silence.
     */
    @Test
    void wobbleOnAnAlreadyFailingToolIsToleratedFarLongerThanARealShift() {
        ToolErrorRate ref = pinned(40_000, 8000); // 20% in control
        long real = callsToAlarm(ref, 0.40, 200_000, new Random(3L));
        long wobble = callsToAlarm(ref, 0.211, 200_000, new Random(3L));

        assertTrue(real > 0 && real < 2_000, "a doubling to 40% should be caught quickly, took " + real);
        assertTrue(
                wobble < 0 || wobble > 20_000,
                "20.0% -> 21.1% should be tolerated for a long time, alarmed at " + wobble);
    }

    /**
     * The bug this rework started from: 5% to 80% took 15,512 calls, because the gate measured the burst against the
     * lifetime average (effect size 0.00004). The denominator is now the run.
     */
    @Test
    void aBurstOnAToolWithLongHistoryIsCaughtInCallsNotThousands() {
        ToolErrorRate ref = pinned(1_000_000, 50_000); // 5% in control, a million calls of history
        long at = callsToAlarm(ref, 0.80, 5_000, new Random(5L));
        assertTrue(at > 0 && at < 40, "an 80% outage should be caught almost immediately, took " + at);
    }

    /**
     * The accumulator reaching zero means the episode is over, so a washed-out burst leaves no onset; otherwise every
     * case dates to the tool's first bad afternoon.
     */
    @Test
    void aBurstThatWashesOutLeavesNoOnset() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 5; i++) s = step(s, ref, CONFIG, true, "2026-08-01T00:00:00Z");
        assertNotNull(s.onsetUpAt(), "evidence accumulating should record where it began");
        for (int i = 0; i < 3000; i++) s = step(s, ref, CONFIG, false, "2026-08-02T00:00:00Z");
        assertEquals(0.0, s.sUp(), "clean traffic should drain the accumulator");
        assertNull(s.onsetUpAt(), "and draining it should forget where the spell started");
    }

    @Test
    void onsetIsTheCallTheRunBeganOnNotTheOneThatAlarmed() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        s = step(s, ref, CONFIG, true, "2026-08-01T09:00:00Z");
        for (int i = 0; i < 400; i++) {
            s = step(s, ref, CONFIG, i % 3 == 0, "2026-08-03T17:00:00Z");
        }
        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired());
        assertEquals("2026-08-01T09:00:00Z", d.onsetAt(), "the case should date to when the rate turned");
    }

    @Test
    void failuresCollapsingOnANoisyToolAlsoAlarms() {
        ToolErrorRate ref = pinned(40_000, 4000); // 10% in control, above downArmMinRate
        State s = State.EMPTY;
        for (int i = 0; i < 5000; i++) s = step(s, ref, CONFIG, false, "2026-08-08T00:00:00Z");
        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired(), "a tool that stopped reporting errors is worth a sentence from someone");
        assertEquals(Direction.DOWN, d.direction());
    }

    /** Below the guard a halving is undetectable, and there is nothing to lose. */
    @Test
    void theImprovementArmDoesNotRunOnAToolThatBarelyFails() {
        ToolErrorRate ref = pinned(40_000, 40); // 0.1%, below downArmMinRate
        State s = State.EMPTY;
        for (int i = 0; i < 20_000; i++) s = step(s, ref, CONFIG, false, "2026-08-08T00:00:00Z");
        assertEquals(0.0, s.sDown());
        assertFalse(ToolErrorDetector.decide(s, ref, CONFIG).fired());
    }

    @Test
    void aToolWithNoReferenceYetSaysSo() {
        Decision d = ToolErrorDetector.decide(State.EMPTY, new ToolErrorRate(), CONFIG);
        assertEquals(Silence.NO_BASELINE, d.silence());
    }

    /** A wait, not a skip (tool-error.md §3.3). */
    @Test
    void aThinReferenceWaitsRatherThanJudging() {
        Decision d = ToolErrorDetector.decide(State.EMPTY, pinned(100, 1), CONFIG);
        assertEquals(Silence.BELOW_MIN_BASELINE, d.silence());
    }

    @Test
    void everySilentDecisionCarriesAReason() {
        for (Decision d : java.util.List.of(
                ToolErrorDetector.decide(State.EMPTY, new ToolErrorRate(), CONFIG),
                ToolErrorDetector.decide(State.EMPTY, pinned(100, 1), CONFIG),
                ToolErrorDetector.decide(State.EMPTY, pinned(20_000, 200), CONFIG))) {
            assertFalse(d.fired());
            assertNotNull(d.silence(), "silence with no reason is indistinguishable from never having been asked");
        }
    }

    /**
     * Without Jeffreys smoothing a spotless tool's rate is zero and {@code ln(p1/0)} infinite: the most alarming case
     * could not be scored.
     */
    @Test
    void aSpotlessToolStillHasAFiniteScore() {
        ToolErrorRate spotless = pinned(20_000, 0);
        double p0 = ToolErrorDetector.baselineRate(spotless);
        assertTrue(p0 > 0 && Double.isFinite(p0), "p0=" + p0);
        State s = step(State.EMPTY, spotless, CONFIG, true, "2026-08-08T00:00:00Z");
        assertTrue(Double.isFinite(s.sUp()) && s.sUp() > 0, "sUp=" + s.sUp());
    }

    @Test
    void theShiftFloorLiftsATinyBaselineToSomethingDetectable() {
        assertTrue(
                ToolErrorDetector.shiftedUp(0.0001, CONFIG) >= 0.005,
                "twice nearly-zero is still nearly-zero; the floor is what makes a clean tool watchable");
    }

    @Test
    void aMultipleOfOneCannotDisableTheDetector() {
        // With p1 == p0 every call scores zero and the detector can never fire.
        ToolErrorConfig c = new ToolErrorConfig(250_000L, 1.0, 0.005, 500, 0.01, 8);
        assertTrue(c.shiftMultiple() > 1.0, "clamped to " + c.shiftMultiple());
    }

    /**
     * The reported rate's denominator dies with the run; one that outlived it would date a finding to 14:03 and quote
     * a rate from last Tuesday.
     */
    @Test
    void theRunDenominatorDiesWithTheRun() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 5; i++) s = step(s, ref, CONFIG, true, "2026-08-01T00:00:00Z");
        assertEquals(5, s.callsSinceOnsetUp(), "the run should count the calls it is holding evidence about");

        for (int i = 0; i < 3000; i++) s = step(s, ref, CONFIG, false, "2026-08-02T00:00:00Z");
        assertEquals(0.0, s.sUp());
        assertEquals(0, s.callsSinceOnsetUp(), "and forget them when the accumulator drains");

        s = step(s, ref, CONFIG, true, "2026-08-03T00:00:00Z");
        assertEquals(1, s.callsSinceOnsetUp(), "a new run starts from one, not from where the old one left off");
    }

    /**
     * Failure counts are derived, not stored, so a wrong inversion would misquote every finding and pin an unagreed
     * reference. Exact within a run.
     */
    @Test
    void theAccumulatorInvertsToTheFailuresBehindIt() {
        ToolErrorRate ref = pinned(20_000, 200);
        double p0 = ToolErrorDetector.baselineRate(ref);
        double p1 = ToolErrorDetector.shiftedUp(p0, CONFIG);

        State s = State.EMPTY;
        Random rng = new Random(42L);
        long failures = 0;
        for (int i = 0; i < 300; i++) {
            boolean failed = rng.nextDouble() < 0.6;
            if (failed) failures++;
            s = step(s, ref, CONFIG, failed, "2026-08-08T00:00:00Z");
        }
        assertTrue(s.sUp() > 0, "the run must still be live for the inversion to be exact");
        assertEquals(failures, ToolErrorDetector.failuresFromS(s.sUp(), s.callsSinceOnsetUp(), p0, p1));
    }

    /** The decision quotes the run, not the tool's history. */
    @Test
    void theDecisionReportsTheRunRateNotTheLifetimeAverage() {
        ToolErrorRate ref = pinned(1_000_000, 50_000); // 5%, with a million calls behind it
        State s = State.EMPTY;
        for (int i = 0; i < 30; i++) s = step(s, ref, CONFIG, true, "2026-08-08T14:03:00Z");

        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired());
        assertEquals(1.0, d.currentRate(), 1e-9, "thirty failures in thirty calls is 100%, not 5.003%");
        assertEquals(30, d.callsSinceOnset());
        assertEquals(30, d.failuresSinceOnset());
        assertEquals("2026-08-08T14:03:00Z", d.onsetAt());
    }

    /**
     * Criticality is read after the crossing: every alarm crosses from below, and how far past the bar the evidence
     * goes separates drift from outage.
     */
    @Test
    void criticalityGrowsWithAccumulatedEvidence() {
        assertEquals(0.0, ToolErrorDetector.criticality(0.0), "nothing accumulated is not a severity");
        assertEquals(0.0, ToolErrorDetector.criticality(1.0));
        double justAlarmed = ToolErrorDetector.criticality(8.4);
        double anHourIn = ToolErrorDetector.criticality(809);
        double aDayIn = ToolErrorDetector.criticality(19_410);
        assertTrue(justAlarmed < anHourIn && anHourIn < aDayIn, justAlarmed + " / " + anHourIn + " / " + aDayIn);
        assertEquals(10.0, ToolErrorDetector.criticality(Math.E * 100) - ToolErrorDetector.criticality(100), 1e-9);
    }

    /**
     * With the old cap a catastrophic and a mild outage pinned to the same number within a day, a flat tie when the
     * queue most needs an order.
     */
    @Test
    void theAccumulatorIsNoLongerCapped() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 2000; i++) s = step(s, ref, CONFIG, true, "2026-08-08T00:00:00Z");
        assertTrue(s.sUp() > 100, "a sustained outage should keep accumulating, got " + s.sUp());
    }
}

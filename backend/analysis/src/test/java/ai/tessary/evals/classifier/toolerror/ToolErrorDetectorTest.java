// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.Silence;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.evals.classifier.toolerror.ToolFailure.Recognized;
import ai.tessary.evals.classifier.toolerror.ToolFailure.Source;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The statistic. {@code classifiers/tool_error/PROGRAM.md} §4. */
class ToolErrorDetectorTest {

    private static final ToolErrorConfig CONFIG = ToolErrorConfig.defaults();
    private static final Recognized BOOM = new Recognized(Source.SPAN_STATUS, "boom");

    /** An in-control reference of {@code calls} calls, {@code failures} of which failed. */
    private static ToolErrorRate pinned(long calls, long failures) {
        ToolErrorRate r = new ToolErrorRate();
        for (long i = 0; i < failures; i++) r.add(BOOM);
        for (long i = failures; i < calls; i++) r.add(null);
        return r;
    }

    /** Feed {@code n} calls at rate {@code p} and return where the run alarmed, or -1 if it never did. */
    private static long callsToAlarm(ToolErrorRate ref, double p, long n, Random rng) {
        State s = State.EMPTY;
        for (long i = 1; i <= n; i++) {
            s = ToolErrorDetector.advance(s, ref, CONFIG, rng.nextDouble() < p, "2026-08-08T00:00:00Z");
            if (ToolErrorDetector.decide(s, ref, CONFIG).fired()) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // The shipped operating point does what it claims
    // ---------------------------------------------------------------------------------------------

    /**
     * The claim {@code DEFAULT_ARL_TARGET} makes is that the derived threshold buys a false alarm roughly
     * every 250,000 calls, on every tool rather than only on clean ones. That number came from a Monte
     * Carlo outside this repo, so this asserts it against the shipping code — loosely, since it is a
     * stochastic quantity and a tight bound would be a flaky test, but tightly enough to catch the failure
     * that matters: a scoring bug that turns the run length into hundreds rather than hundreds of
     * thousands.
     *
     * <p>Fixed seed. A calibration test that fails one run in twenty is a test people learn to re-run.
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
        // 12 runs x 40k calls = 480k in-control calls. At the designed ARL0 of ~250k the expectation is
        // about two. Four or more would put the true run length near 100k, which is a different detector
        // from the one the javadoc describes.
        assertTrue(alarms <= 4, alarms + " false alarms in 480k in-control calls — h is not buying its ARL");
    }

    /** The other half: a run length that long is worthless if a real regression never trips it. */
    @Test
    void aSustainedDoublingIsCaughtWithinAFewThousandCalls() {
        ToolErrorRate ref = pinned(20_000, 200); // 1% in control
        long at = callsToAlarm(ref, 0.02, 40_000, new Random(11L));
        assertTrue(at > 0, "a sustained doubling never alarmed");
        assertTrue(at < 10_000, "took " + at + " calls to notice a doubling");
    }

    /**
     * The case a percentage-point threshold cannot see and the reason {@code shiftFloor} exists: twice
     * nearly-zero is still nearly-zero, so without the floor the detector would be tuned for a shift too
     * small to distinguish from silence on exactly the tool whose failing matters most.
     */
    @Test
    void aToolThatNeverFailedAndStartsFailingIsCaught() {
        ToolErrorRate ref = pinned(20_000, 0);
        long at = callsToAlarm(ref, 0.01, 40_000, new Random(7L));
        assertTrue(at > 0, "a clean tool starting to fail 1 in 100 never alarmed");
        assertTrue(at < 5_000, "took " + at + " calls");
    }

    /**
     * The case that used to force an effect-size gate to exist, and still the most instructive test here.
     *
     * <p>A sustained 20.0% -> 21.1% is a real change in the process and is also nobody's problem. A
     * classifier that opens a case for it teaches a partner to stop reading it. This used to be handled
     * downstream: the accumulator crossed after about 3,000 calls and {@code minEffectSize} declined to
     * make a case of it. That gate measured the shift over every call since the reference was pinned,
     * which on any tool with history is the baseline by construction, so it also silenced every real
     * outage. It is gone.
     *
     * <p>What holds the line now is the threshold itself. A flat 6.0 gave this tool a false-alarm run
     * length of about 5,288 calls — forty-seven times hotter than intended — and the wobble was riding
     * that leak. At the derived {@code h} of ~9.9 the accumulator's own break-even rate does the work:
     * 21.1% sits below it, so the statistic drifts down and only a lucky excursion ever reaches the bar.
     *
     * <p><b>Tolerated, not impossible.</b> A sequential test can make a real-but-small shift rare and
     * cannot make it never, so this asserts the separation rather than silence: a shift the detector is
     * tuned for is caught almost immediately, and the wobble survives orders of magnitude longer.
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
     * The reason one dial works across every tool: the bar moves with how noisy the tool already is.
     *
     * <p>Four failures in a row damns a tool that fails one call in a thousand and is a Tuesday on one
     * that fails one in five. A flat threshold treats those the same and is therefore wrong on both; this
     * is the assertion that it no longer does.
     */
    @Test
    void theThresholdRisesWithTheToolsOwnNoise() {
        double clean = CONFIG.decisionIntervalFor(0.005);
        double typical = CONFIG.decisionIntervalFor(0.05);
        double noisy = CONFIG.decisionIntervalFor(0.20);
        assertTrue(clean < typical && typical < noisy, clean + " / " + typical + " / " + noisy);
        assertTrue(typical > 7.7 && typical < 8.7, "a 5% tool should need about 8.2, got " + typical);
        assertTrue(noisy > 9.2 && noisy < 10.2, "a 20% tool should need about 9.7, got " + noisy);
    }

    /**
     * The bug this whole rework started from. A tool sitting at 5% goes to 80%, and the finding has to
     * arrive in calls rather than in hours.
     *
     * <p>It used to take 15,512 calls, because the magnitude gate measured the burst against the tool's
     * lifetime average — 5.001% against 5%, an effect size of 0.00004 — and declined it until the burst
     * had diluted a million healthy calls. The denominator is now the run, so the same burst reads 80%.
     */
    @Test
    void aBurstOnAToolWithLongHistoryIsCaughtInCallsNotThousands() {
        ToolErrorRate ref = pinned(1_000_000, 50_000); // 5% in control, a million calls of history
        long at = callsToAlarm(ref, 0.80, 5_000, new Random(5L));
        assertTrue(at > 0 && at < 40, "an 80% outage should be caught almost immediately, took " + at);
    }

    // ---------------------------------------------------------------------------------------------
    // Onset — the thing a window scheme could not recover
    // ---------------------------------------------------------------------------------------------

    /**
     * The accumulator returning to zero IS the statement that whatever happened is over, so a burst that
     * the following successes wash out must leave no onset behind. Otherwise every case would date itself
     * to the first bad afternoon the tool ever had.
     */
    @Test
    void aBurstThatWashesOutLeavesNoOnset() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 5; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-01T00:00:00Z");
        assertNotNull(s.onsetUpAt(), "evidence accumulating should record where it began");
        for (int i = 0; i < 3000; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, false, "2026-08-02T00:00:00Z");
        assertEquals(0.0, s.sUp(), "clean traffic should drain the accumulator");
        assertNull(s.onsetUpAt(), "and draining it should forget where the spell started");
    }

    @Test
    void onsetIsTheCallTheRunBeganOnNotTheOneThatAlarmed() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-01T09:00:00Z");
        for (int i = 0; i < 400; i++) {
            s = ToolErrorDetector.advance(s, ref, CONFIG, i % 3 == 0, "2026-08-03T17:00:00Z");
        }
        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired());
        assertEquals("2026-08-01T09:00:00Z", d.onsetAt(), "the case should date to when the rate turned");
    }

    // ---------------------------------------------------------------------------------------------
    // Both directions, and the guard on the improvement arm
    // ---------------------------------------------------------------------------------------------

    @Test
    void failuresCollapsingOnANoisyToolAlsoAlarms() {
        ToolErrorRate ref = pinned(40_000, 4000); // 10% in control, above downArmMinRate
        State s = State.EMPTY;
        for (int i = 0; i < 5000; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, false, "2026-08-08T00:00:00Z");
        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired(), "a tool that stopped reporting errors is worth a sentence from someone");
        assertEquals(Direction.DOWN, d.direction());
    }

    /** Below the guard there is nothing to lose, and a halving of it is undetectable anyway. */
    @Test
    void theImprovementArmDoesNotRunOnAToolThatBarelyFails() {
        ToolErrorRate ref = pinned(40_000, 40); // 0.1%, below downArmMinRate
        State s = State.EMPTY;
        for (int i = 0; i < 20_000; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, false, "2026-08-08T00:00:00Z");
        assertEquals(0.0, s.sDown());
        assertFalse(ToolErrorDetector.decide(s, ref, CONFIG).fired());
    }

    // ---------------------------------------------------------------------------------------------
    // Silence always explains itself
    // ---------------------------------------------------------------------------------------------

    @Test
    void aToolWithNoReferenceYetSaysSo() {
        Decision d = ToolErrorDetector.decide(State.EMPTY, new ToolErrorRate(), CONFIG);
        assertEquals(Silence.NO_BASELINE, d.silence());
    }

    /** A wait, not a skip — the distinction PROGRAM.md §3.3 turns on. */
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

    // ---------------------------------------------------------------------------------------------
    // The arithmetic that would break silently
    // ---------------------------------------------------------------------------------------------

    /**
     * Without Jeffreys smoothing a spotless tool has a raw in-control rate of exactly zero and
     * {@code ln(p1/0)} is infinite — so the unsmoothed estimator makes the most alarming case in the
     * product the one case the detector cannot score at all.
     */
    @Test
    void aSpotlessToolStillHasAFiniteScore() {
        ToolErrorRate spotless = pinned(20_000, 0);
        double p0 = ToolErrorDetector.baselineRate(spotless);
        assertTrue(p0 > 0 && Double.isFinite(p0), "p0=" + p0);
        State s = ToolErrorDetector.advance(State.EMPTY, spotless, CONFIG, true, "2026-08-08T00:00:00Z");
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
        // ln(p1/p0) with p1 == p0 is zero on every call, which is a detector that can never fire.
        ToolErrorConfig c = new ToolErrorConfig(250_000L, 1.0, 0.005, 0.05, 500, 0.01, 300, 8);
        assertTrue(c.shiftMultiple() > 1.0, "clamped to " + c.shiftMultiple());
    }

    // ---------------------------------------------------------------------------------------------
    // The run, and reading it back out of the accumulator
    // ---------------------------------------------------------------------------------------------

    /**
     * The reported rate has to span the run the onset names, so its denominator dies with the run. A
     * counter that outlived it would date a finding to 14:03 and then quote a rate measured from last
     * Tuesday, which is the bug this rework exists to fix in its other form.
     */
    @Test
    void theRunDenominatorDiesWithTheRun() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 5; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-01T00:00:00Z");
        assertEquals(5, s.callsSinceOnsetUp(), "the run should count the calls it is holding evidence about");

        for (int i = 0; i < 3000; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, false, "2026-08-02T00:00:00Z");
        assertEquals(0.0, s.sUp());
        assertEquals(0, s.callsSinceOnsetUp(), "and forget them when the accumulator drains");

        s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-03T00:00:00Z");
        assertEquals(1, s.callsSinceOnsetUp(), "a new run starts from one, not from where the old one left off");
    }

    /**
     * Failure counts are derived rather than stored, so if the inversion is wrong every finding quotes a
     * rate nobody can trace and absorption pins a reference nobody agreed to. Exact within a run, because
     * a run is a stretch over which the accumulator never touched its floor.
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
            s = ToolErrorDetector.advance(s, ref, CONFIG, failed, "2026-08-08T00:00:00Z");
        }
        assertTrue(s.sUp() > 0, "the run must still be live for the inversion to be exact");
        assertEquals(failures, ToolErrorDetector.failuresFromS(s.sUp(), s.callsSinceOnsetUp(), p0, p1));
    }

    /** And the decision quotes that run, not the tool's history. */
    @Test
    void theDecisionReportsTheRunRateNotTheLifetimeAverage() {
        ToolErrorRate ref = pinned(1_000_000, 50_000); // 5%, with a million calls behind it
        State s = State.EMPTY;
        for (int i = 0; i < 30; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-08T14:03:00Z");

        Decision d = ToolErrorDetector.decide(s, ref, CONFIG);
        assertTrue(d.fired());
        assertEquals(1.0, d.currentRate(), 1e-9, "thirty failures in thirty calls is 100%, not 5.003%");
        assertEquals(30, d.callsSinceOnset());
        assertEquals(30, d.failuresSinceOnset());
        assertEquals("2026-08-08T14:03:00Z", d.onsetAt());
    }

    /**
     * Criticality is read after the crossing, never at it: every alarm crosses from below, so the
     * statistic at that moment says only that the bar was reached. What separates a mild drift from an
     * outage is how far past it the evidence keeps going.
     */
    @Test
    void criticalityGrowsWithAccumulatedEvidence() {
        assertEquals(0.0, ToolErrorDetector.criticality(0.0), "nothing accumulated is not a severity");
        assertEquals(0.0, ToolErrorDetector.criticality(1.0));
        double justAlarmed = ToolErrorDetector.criticality(8.4);
        double anHourIn = ToolErrorDetector.criticality(809);
        double aDayIn = ToolErrorDetector.criticality(19_410);
        assertTrue(justAlarmed < anHourIn && anHourIn < aDayIn, justAlarmed + " / " + anHourIn + " / " + aDayIn);
        // Natural log, so every 10 points is 2.72x more evidence.
        assertEquals(10.0, ToolErrorDetector.criticality(Math.E * 100) - ToolErrorDetector.criticality(100), 1e-9);
    }

    /**
     * The ceiling is gone, and this is the property that removal exists to protect: a catastrophic outage
     * and a mild one used to pin to the same number within a day, which made criticality a flat tie
     * exactly when a work queue most needs an order.
     */
    @Test
    void theAccumulatorIsNoLongerCapped() {
        ToolErrorRate ref = pinned(20_000, 200);
        State s = State.EMPTY;
        for (int i = 0; i < 2000; i++) s = ToolErrorDetector.advance(s, ref, CONFIG, true, "2026-08-08T00:00:00Z");
        assertTrue(s.sUp() > 100, "a sustained outage should keep accumulating, got " + s.sUp());
    }
}

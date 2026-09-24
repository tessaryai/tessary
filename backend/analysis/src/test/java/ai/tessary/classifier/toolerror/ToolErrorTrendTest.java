// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Sweep;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The replay. {@code devdocs/concepts/tool-error.md} §5.
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
                        null,
                        null));

        series(s, 40, 60, 200, 0.01); // the tool is genuinely fixed
        assertTrue(
                ToolErrorTrend.sweep(s, CONFIG, Map.of(), cleared).spells().isEmpty(),
                "cleared evidence plus a healthy tool must not re-raise the same spell");
    }

    /**
     * The same reset, on a replay that rebuilds rather than resumes. Malformed Output rebuilds every pass, and a
     * tuning change rebuilds tool_error too. With no watermark to resume from, the rebuild would re-fold the
     * hours before the reset and hand back the spell a human just closed, so it skips them instead.
     */
    @Test
    void aResetFencesARebuild() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 20, 200, 0.05);
        Sweep broken = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of());
        assertEquals(1, broken.spells().size());
        CarriedState was = broken.advanced().get(0);

        // What a rebuilding caller hands in after a reset: arms and watermark cleared, reference kept, and
        // the reset stamped mid-hour, after the last broken bucket.
        String resetAt = START.plus(Duration.ofHours(39))
                .plusSeconds(1234)
                .plusMillis(567)
                .toString();
        CarriedState rebuilding = new CarriedState(
                TOOL, ToolErrorDetector.State.EMPTY, was.baseline(), null, was.stateEpoch(), null, null, resetAt);

        series(s, 40, 60, 200, 0.01); // healthy again
        Sweep after = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of(TOOL, rebuilding));
        assertTrue(after.spells().isEmpty(), "the rebuild must not re-accumulate the hours before the reset");
        assertEquals(0.0, after.advanced().get(0).state().sUp(), 1e-9);
        assertEquals(resetAt, after.advanced().get(0).resetAt(), "the fence rides through to the next pass");

        Sweep unfenced = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of(TOOL, withoutReset(rebuilding)));
        assertEquals(1, unfenced.spells().size(), "and without the fence the closed spell comes straight back");
    }

    /** A reset that also dropped the reference re-learns it from the traffic after the reset, never before. */
    @Test
    void aResetWithoutAReferenceRelearnsOnlyFromAfterIt() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 20, 200, 0.05); // the degraded stretch a human resolved
        String resetAt = START.plus(Duration.ofHours(40)).toString();
        CarriedState relearning = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                CarriedState.epochOf(CONFIG, ToolErrorTrend.STATE_SCHEMA_VERSION),
                null,
                null,
                resetAt);

        assertNull(
                ToolErrorTrend.replay(TOOL, s, CONFIG, null, relearning),
                "every hour so far is before the reset, so there is nothing to learn from");

        series(s, 40, 10, 200, 0.05); // the post-reset traffic runs at 5%, and that is the new normal
        Sweep after = ToolErrorTrend.sweep(s, CONFIG, Map.of(), Map.of(TOOL, relearning));
        ToolErrorRate learned = requireBaseline(after);
        assertEquals(600, learned.calls(), "the leading post-reset traffic, once");
        assertTrue(learned.rate() > 0.04, "learned from after the reset, not from the healthy hours before it");
        assertTrue(after.spells().isEmpty(), "and the rate it learned is not judged against itself");
    }

    private static CarriedState withoutReset(CarriedState c) {
        return new CarriedState(
                c.toolKey(),
                c.state(),
                c.baseline(),
                c.watermarkBucket(),
                c.stateEpoch(),
                c.pendingPinBy(),
                c.pendingPinAt(),
                null);
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
    // The event clock a finding is written from
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code ToolErrorService} writes a finding's {@code last_seen_at} from this rather than from
     * wall-clock now, so a backfilled replay reports when the traffic actually happened rather than
     * when the sweep happened to run.
     */
    @Test
    void aSpellReportsTheLastHourItFolded() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);

        Spell spell = spells(s).get(0);
        assertEquals(
                s.get(s.size() - 1).bucket(),
                spell.lastBucket(),
                "the spell's event clock is the last hour actually folded, not when the replay ran");
    }

    /** And it advances only across buckets a resumed sweep actually reads, never past them. */
    @Test
    void aResumedSweepReportsOnlyAsFarAsItActuallyFolded() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        series(s, 20, 30, 200, 0.05);

        Sweep first = ToolErrorTrend.sweep(s.subList(0, 40), CONFIG, Map.of(), Map.of());
        assertEquals(s.get(39).bucket(), first.spells().get(0).lastBucket());

        Sweep resumed = ToolErrorTrend.sweep(s, CONFIG, Map.of(), carriedFrom(first));
        assertEquals(s.get(s.size() - 1).bucket(), resumed.spells().get(0).lastBucket());
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

    // ---------------------------------------------------------------------------------------------
    // Judging from one reference size while learning to another
    // ---------------------------------------------------------------------------------------------

    /** Judges from 500 calls, as tool_error does, and keeps learning the reference until 2,000. */
    private static final ToolErrorConfig LEARNING = withFreeze(CONFIG, 2000);

    private static ToolErrorConfig withFreeze(ToolErrorConfig c, int freeze) {
        return new ToolErrorConfig(
                c.arlTarget(),
                c.shiftMultiple(),
                c.shiftFloor(),
                c.minEffectSize(),
                c.minBaselineCalls(),
                c.downArmMinRate(),
                c.settleSeconds(),
                c.maxPatterns(),
                c.minDecisionInterval(),
                freeze);
    }

    /**
     * Every scenario above, as one string per sweep: the spells with their numbers, and every carried row. Two
     * configs that agree on all of it behave the same to every caller, resumed, rebuilt or fenced.
     */
    private static List<String> scenarios(ToolErrorConfig config) {
        List<HourlyToolTally> degraded = series(20, 200, 0.01);
        series(degraded, 20, 30, 200, 0.05);
        List<HourlyToolTally> recovered = series(20, 200, 0.01);
        series(recovered, 20, 20, 200, 0.05);
        series(recovered, 40, 60, 200, 0.01);
        List<HourlyToolTally> ramp = series(20, 200, 0.01);
        double rate = 0.01;
        for (int h = 0; h < 60; h++) {
            rate += 0.0008;
            series(ramp, 20 + h, 1, 200, rate);
        }
        List<HourlyToolTally> twoTools = new ArrayList<>();
        for (int h = 0; h < 50; h++) {
            String at = START.plus(Duration.ofHours(h)).toString();
            twoTools.add(new HourlyToolTally(at, "tool:healthy", 200, 2));
            twoTools.add(new HourlyToolTally(at, "tool:broken", 200, h < 20 ? 2 : 10));
        }

        List<Sweep> sweeps = new ArrayList<>();
        for (List<HourlyToolTally> s :
                List.of(degraded, recovered, ramp, twoTools, series(10, 10, 0.20), series(400, 200, 0.01))) {
            sweeps.add(ToolErrorTrend.sweep(s, config, Map.of(), Map.of()));
        }

        // Resumed in pieces, resumed over the same hours, and after the window slid.
        Sweep first = ToolErrorTrend.sweep(degraded.subList(0, 30), config, Map.of(), Map.of());
        Sweep second = ToolErrorTrend.sweep(degraded.subList(0, 40), config, Map.of(), carriedFrom(first));
        sweeps.add(first);
        sweeps.add(second);
        sweeps.add(ToolErrorTrend.sweep(degraded, config, Map.of(), carriedFrom(second)));
        Sweep whole = ToolErrorTrend.sweep(degraded, config, Map.of(), Map.of());
        sweeps.add(ToolErrorTrend.sweep(degraded, config, Map.of(), carriedFrom(whole)));
        sweeps.add(ToolErrorTrend.sweep(degraded.subList(25, degraded.size()), config, Map.of(), carriedFrom(whole)));

        // Rebuilt every pass, as frustration and malformed output do, then fenced, then relearning.
        CarriedState was = whole.advanced().get(0);
        sweeps.add(ToolErrorTrend.sweep(degraded, config, Map.of(), Map.of(TOOL, was.rebuilding())));
        String resetAt = START.plus(Duration.ofHours(39)).plusSeconds(1234).toString();
        CarriedState fenced = new CarriedState(
                TOOL, ToolErrorDetector.State.EMPTY, was.baseline(), null, was.stateEpoch(), null, null, resetAt);
        sweeps.add(ToolErrorTrend.sweep(recovered, config, Map.of(), Map.of(TOOL, fenced)));
        CarriedState relearning = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                CarriedState.epochOf(config, ToolErrorTrend.STATE_SCHEMA_VERSION),
                null,
                null,
                START.plus(Duration.ofHours(40)).toString());
        sweeps.add(ToolErrorTrend.sweep(recovered, config, Map.of(), Map.of(TOOL, relearning)));

        return sweeps.stream().map(ToolErrorTrendTest::describe).toList();
    }

    private static String describe(Sweep sweep) {
        StringBuilder out = new StringBuilder();
        for (Spell s : sweep.spells()) {
            out.append("spell ")
                    .append(s.toolKey())
                    .append(' ')
                    .append(s.decision())
                    .append(" baseline=")
                    .append(s.baseline().calls())
                    .append('/')
                    .append(s.baseline().failures())
                    .append(" observed=")
                    .append(s.observed().calls())
                    .append('/')
                    .append(s.observed().failures())
                    .append(' ')
                    .append(s.onsetBucket())
                    .append(' ')
                    .append(s.lastBucket())
                    .append('\n');
        }
        for (CarriedState c : sweep.advanced()) {
            ToolErrorRate b = c.baseline();
            out.append("carried ")
                    .append(c.toolKey())
                    .append(' ')
                    .append(c.state())
                    .append(" baseline=")
                    .append(b == null ? "none" : b.calls() + "/" + b.failures())
                    .append(' ')
                    .append(c.watermarkBucket())
                    .append(' ')
                    .append(c.stateEpoch())
                    .append(' ')
                    .append(c.resetAt())
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * The default is the old engine exactly. Every classifier that sets no freeze, which today is all of them,
     * must see the same spells, the same numbers and the same stored rows as before the dial existed.
     */
    @Test
    void withTheFreezeEqualToTheMinimumNothingChanges() {
        ToolErrorConfig explicit = withFreeze(CONFIG, CONFIG.minBaselineCalls());
        assertEquals(CONFIG.minBaselineCalls(), CONFIG.freezeBaselineCalls());
        assertEquals(scenarios(CONFIG), scenarios(explicit));

        ToolErrorConfig frustrationShaped = new ToolErrorConfig(10_000L, 2.0, 0.02, 0.05, 200, 0.01, 300, 8, 4.0);
        assertEquals(scenarios(frustrationShaped), scenarios(withFreeze(frustrationShaped, 200)));
    }

    @Test
    void judgingStartsAtTheMinimumAndTheReferenceKeepsLearningUntilTheFreeze() {
        List<HourlyToolTally> s = series(5, 200, 0.01); // 1,000 calls: past the minimum, short of the freeze

        Sweep early = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(1, early.advanced().size(), "judging has started, so the row is carried");
        assertEquals(1000, requireBaseline(early).calls(), "and every judged hour was learned from afterwards");
        assertEquals(s.get(4).bucket(), early.advanced().get(0).watermarkBucket());

        series(s, 5, 15, 200, 0.01);
        Sweep full = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(2000, requireBaseline(full).calls(), "learning stops at the freeze");
        CarriedState row = full.advanced().get(0);
        assertEquals(s.get(19).bucket(), row.watermarkBucket());

        assertTrue(full.spells().isEmpty(), "a healthy tool is silent while its reference learns");

        // The first three hours are the minimum and are never judged; every hour after them is.
        List<HourlyToolTally> spiked = new ArrayList<>(s.subList(0, 3));
        series(spiked, 3, 10, 200, 0.10);
        Sweep judged = ToolErrorTrend.sweep(spiked, LEARNING, Map.of(), Map.of());
        assertEquals(1, judged.spells().size(), "judged from the minimum, not from the freeze");
        assertEquals(2000, judged.spells().get(0).observed().calls(), "every hour after the minimum, once");

        // A resumed sweep grows the reference by exactly the hours after its watermark.
        Sweep resumed = ToolErrorTrend.sweep(s, LEARNING, Map.of(), carriedFrom(early));
        assertEquals(2000, requireBaseline(resumed).calls(), "resuming learns each hour once");
        assertEquals(row.state(), resumed.advanced().get(0).state(), "and agrees with the full replay");
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

    @Test
    void theReferenceStopsGrowingAtTheFreeze() {
        List<HourlyToolTally> s = series(40, 200, 0.01);
        Sweep first = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(2000, requireBaseline(first).calls());

        series(s, 40, 30, 200, 0.05);
        Sweep resumed = ToolErrorTrend.sweep(s, LEARNING, Map.of(), carriedFrom(first));
        assertEquals(2000, requireBaseline(resumed).calls(), "a frozen reference does not grow on a resume");
        assertEquals(1, resumed.spells().size(), "and the rise after it is judged against it");

        Map<String, CarriedState> rebuilt =
                Map.of(TOOL, resumed.advanced().get(0).rebuilding());
        Sweep again = ToolErrorTrend.sweep(s.subList(20, s.size()), LEARNING, Map.of(), rebuilt);
        assertEquals(2000, requireBaseline(again).calls(), "nor on a rebuild, after the window slid");
        assertTrue(requireBaseline(again).rate() < 0.02, "it is still the healthy traffic that taught it");

        // Buckets are learned whole, so the one that crosses the freeze is the last one learned.
        Sweep overshoot = ToolErrorTrend.sweep(s, withFreeze(CONFIG, 1900), Map.of(), Map.of());
        assertEquals(2000, requireBaseline(overshoot).calls());
    }

    /**
     * A rebuilding caller (frustration, malformed output, and groundedness next) hands in a still-learning
     * reference with no watermark. It keeps that reference and learns only the hours after the ones it holds:
     * growing it from every hour in the window would count those hours twice.
     */
    @Test
    void aRebuildKeepsALearningReferenceAndLearnsOnlyTheNewHours() {
        List<HourlyToolTally> s = series(7, 200, 0.01); // 1,400 calls, still learning
        Sweep first = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(1400, requireBaseline(first).calls());

        Sweep rebuilt = ToolErrorTrend.sweep(
                s, LEARNING, Map.of(), Map.of(TOOL, first.advanced().get(0).rebuilding()));
        assertEquals(1400, requireBaseline(rebuilt).calls(), "the same hours, learned once");

        series(s, 7, 2, 200, 0.01);
        Sweep grown = ToolErrorTrend.sweep(
                s, LEARNING, Map.of(), Map.of(TOOL, rebuilt.advanced().get(0).rebuilding()));
        assertEquals(1800, requireBaseline(grown).calls(), "and then only the hours after them");
    }

    /**
     * A rebuilding caller replays a window that slides. Its reference must still be the leading traffic, frozen
     * once it reaches the freeze, and not whatever the window holds now: that reference would rise with a slow
     * degradation and never see it.
     */
    @Test
    void aRebuildingCallerFreezesItsReferenceWhileTheWindowSlides() {
        ToolErrorConfig config =
                withFreeze(new ToolErrorConfig(10_000L, 2.0, 0.02, 0.05, 200, 0.01, 300, 8, 4.0), 1000);
        List<HourlyToolTally> days = new ArrayList<>();
        for (int d = 0; d < 120; d++) {
            double rate = d < 40 ? 0.03 : Math.min(0.30, 0.03 + (d - 40) * 0.005);
            String at = START.plus(Duration.ofDays(d)).toString();
            days.add(new HourlyToolTally(at, TOOL, 30, Math.round(30 * rate)));
        }

        Map<String, CarriedState> carried = Map.of();
        ToolErrorRate reference = null;
        boolean fired = false;
        for (int end = 10; end <= days.size(); end += 10) {
            Sweep sweep = ToolErrorTrend.sweep(days.subList(Math.max(0, end - 28), end), config, Map.of(), carried);
            reference = requireBaseline(sweep);
            fired |= !sweep.spells().isEmpty();
            carried = Map.of(TOOL, sweep.advanced().get(0).rebuilding());
        }
        assertNotNull(reference);
        assertEquals(1020, reference.calls(), "frozen at the first day past the freeze, though the window slid");
        assertEquals(34, reference.failures(), "and learned from the healthy leading days only");
        assertTrue(fired, "so the slow rise is caught");
    }

    /** A reset that keeps the reference, on a rebuilding caller: the hours it held before the fence stay. */
    @Test
    void aResetThatKeepsALearningReferenceKeepsWhatItLearned() {
        List<HourlyToolTally> s = series(7, 200, 0.01); // 1,400 calls, still learning
        CarriedState was =
                ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of()).advanced().get(0);

        // What reset leaves: the reference and the watermark kept, the accumulator cleared, the fence at hour 8.
        String resetAt = START.plus(Duration.ofHours(8)).toString();
        CarriedState reset = new CarriedState(
                TOOL,
                ToolErrorDetector.State.EMPTY,
                was.baseline(),
                was.watermarkBucket(),
                was.stateEpoch(),
                null,
                null,
                resetAt);

        series(s, 7, 3, 200, 0.10); // hour 7 is before the fence; hours 8 and 9 after it
        Sweep rebuilt = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of(TOOL, reset.rebuilding()));
        ToolErrorRate reference = requireBaseline(rebuilt);
        assertEquals(1800, reference.calls(), "the hours it held, then the hours after the fence, not the one before");
        assertEquals(14 + 40, reference.failures());
        assertEquals(resetAt, rebuilt.advanced().get(0).resetAt());
    }

    @Test
    void aResetRelearnsFromTheFenceUpToTheFreezeAgain() {
        List<HourlyToolTally> s = series(20, 200, 0.01);
        Sweep learned = ToolErrorTrend.sweep(s, LEARNING, Map.of(), Map.of());
        assertEquals(2000, requireBaseline(learned).calls());

        // What resetAndRelearn leaves: no reference, no watermark, and the fence at the hour it was pressed.
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
        assertEquals(1000, requireBaseline(partway).calls(), "learning again from the fence, not before it");
        assertTrue(requireBaseline(partway).rate() > 0.04);
        assertTrue(partway.spells().isEmpty(), "and the new normal is not judged against the old one");

        series(s, 25, 20, 200, 0.05);
        Sweep resumed = ToolErrorTrend.sweep(s, LEARNING, Map.of(), carriedFrom(partway));
        assertEquals(2000, requireBaseline(resumed).calls(), "up to the freeze again, and no further");
        assertEquals(resetAt, resumed.advanced().get(0).resetAt());

        Map<String, CarriedState> rebuilding =
                Map.of(TOOL, partway.advanced().get(0).rebuilding());
        Sweep rebuilt = ToolErrorTrend.sweep(s, LEARNING, Map.of(), rebuilding);
        assertEquals(2000, requireBaseline(rebuilt).calls(), "a rebuild relearns from the fence too");
        assertTrue(requireBaseline(rebuilt).rate() > 0.04);
    }
}

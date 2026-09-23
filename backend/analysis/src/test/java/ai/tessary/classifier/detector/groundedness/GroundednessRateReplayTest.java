// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Sweep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The rate test over groundedness-shaped tallies: a trace is a trial, one with a flagged answer a failure, and
 * the shared engine runs on {@link GroundednessConfig}'s defaults (judged from 200 traces, the reference learning
 * until 1,000, a floor of 4 on {@code h}, a 50,000-trace false-alarm budget).
 *
 * <p>That two flagged answers in one trace are one failure is the tally query's doing, so it is held against
 * Postgres in {@code GroundednessRateIntegrationTest}; everything here starts from the hourly tallies.
 */
class GroundednessRateReplayTest {

    private static final GroundednessConfig CONFIG = GroundednessConfig.defaults();
    private static final String CALL_SITE = "cs-rag-answer";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int PER_HOUR = 20;

    private static String hour(int h) {
        return START.plus(Duration.ofHours(h)).toString();
    }

    /** {@code hours} buckets of {@link #PER_HOUR} traces flagged at {@code rate}, carrying remainders. */
    private static List<HourlyToolTally> series(List<HourlyToolTally> into, int fromHour, int hours, double rate) {
        double owed = 0;
        for (int h = 0; h < hours; h++) {
            owed += PER_HOUR * rate;
            long flagged = (long) Math.floor(owed + 1e-9);
            owed -= flagged;
            into.add(new HourlyToolTally(hour(fromHour + h), CALL_SITE, PER_HOUR, flagged));
        }
        return into;
    }

    private static Sweep sweep(List<HourlyToolTally> tallies, Map<String, CarriedState> carried) {
        return ToolErrorTrend.sweep(tallies, CONFIG.engine(), Map.of(), carried, CONFIG.schemaVersion());
    }

    private static Sweep sweep(List<HourlyToolTally> tallies) {
        return sweep(tallies, Map.of());
    }

    private static ToolErrorRate baselineOf(Sweep sweep) {
        ToolErrorRate baseline = sweep.advanced().get(0).baseline();
        assertNotNull(baseline);
        return baseline;
    }

    private static List<Spell> rising(Sweep sweep) {
        return sweep.spells().stream()
                .filter(sp -> sp.decision().direction() == Direction.UP)
                .toList();
    }

    @Test
    void aCallSiteStillBelow200TracesIsNotJudged() {
        Sweep sweep = sweep(series(new ArrayList<>(), 0, 9, 0.50));
        assertTrue(sweep.advanced().isEmpty(), "180 traces is still learning");
        assertTrue(sweep.spells().isEmpty());
    }

    @Test
    void theReferenceKeepsLearningUntil1000TracesThenFreezes() {
        Sweep at600 = sweep(series(new ArrayList<>(), 0, 30, 0.05));
        assertEquals(600, baselineOf(at600).calls(), "judged from 200 and still learning at 600");

        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 60, 0.05);
        Sweep first = sweep(s);
        ToolErrorRate learned = baselineOf(first);
        assertEquals(1_000, learned.calls(), "frozen at 1,000");
        assertEquals(50, learned.failures());

        // The window slides past the hours it learned from and the traffic worsens; a rebuild keeps the reference.
        List<HourlyToolTally> slid = new ArrayList<>(s.subList(30, 60));
        series(slid, 60, 20, 0.30);
        Sweep second = sweep(slid, Map.of(CALL_SITE, first.advanced().get(0).rebuilding()));
        assertEquals(1_000, baselineOf(second).calls());
        assertEquals(50, baselineOf(second).failures());
        assertEquals(CONFIG.stateEpoch(), second.advanced().get(0).stateEpoch());
    }

    @Test
    void aDoublingFromFivePercentAlarms() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 50, 0.05); // a frozen reference of 1,000
        int hours = 0;
        while (rising(sweep(s)).isEmpty()) {
            series(s, 50 + hours, 1, 0.10);
            hours++;
            assertTrue(hours < 100, "a doubling is caught within 2,000 traces");
        }
        Spell spell = rising(sweep(s)).get(0);
        assertEquals(CALL_SITE, spell.toolKey());
        assertTrue(hours * PER_HOUR <= 800, "caught after " + hours * PER_HOUR + " traces");
    }

    @Test
    void aRiseWhileTheReferenceIsStillLearningIsCaught() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05); // 200: judging starts
        int hours = 0;
        while (rising(sweep(s)).isEmpty()) {
            series(s, 10 + hours, 1, 0.25);
            hours++;
            assertTrue(hours < 40, "caught before the reference would have frozen");
        }
        assertTrue(baselineOf(sweep(s)).calls() < CONFIG.freezeBaselineTraces(), "caught while still learning");
    }

    @Test
    void aSteadyFivePercentIsSilentOver5000Traces() {
        // A seeded run, not a bound: the budget is one false finding per 50,000 traces.
        Random random = new Random(20260923L);
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        for (int h = 10; h < 10 + 250; h++) {
            long flagged = 0;
            for (int c = 0; c < PER_HOUR; c++) if (random.nextDouble() < 0.05) flagged++;
            s.add(new HourlyToolTally(hour(h), CALL_SITE, PER_HOUR, flagged));
            assertTrue(rising(sweep(s)).isEmpty(), "a steady call site alarmed at hour " + h);
        }
    }

    @Test
    void aSlowRampIsCaught() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 50, 0.05);
        for (int h = 0; h < 150; h++) series(s, 50 + h, 1, 0.05 + 0.10 * h / 150.0);
        List<Spell> spells = rising(sweep(s));
        assertEquals(1, spells.size());
    }

    @Test
    void aLateFlagReReadIntoItsOriginalHourMovesTheAccumulatorOnTheNextPass() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 50, 0.05);
        series(s, 50, 6, 0.10);
        Sweep first = sweep(s);
        double before = first.advanced().get(0).state().sUp();

        // Another answer of a trace first scored at hour 52 was flagged later; the replay reads it there.
        List<HourlyToolTally> reread = new ArrayList<>(s);
        HourlyToolTally h52 = reread.get(52);
        reread.set(52, new HourlyToolTally(h52.bucket(), CALL_SITE, h52.calls(), h52.failures() + 2));
        Sweep second = sweep(reread, Map.of(CALL_SITE, first.advanced().get(0).rebuilding()));

        assertTrue(second.advanced().get(0).state().sUp() > before);
        assertEquals(baselineOf(first).calls(), baselineOf(second).calls(), "the frozen reference does not move");
    }

    @Test
    void aResetFencesTheHoursBeforeItAndTheReferenceIsReLearnedAfterIt() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 50, 0.05);
        series(s, 50, 30, 0.25);
        assertEquals(1, rising(sweep(s)).size());

        // resetAndRelearn: accumulator, onset and reference cleared, reset_at written.
        String resetAt = START.plus(Duration.ofHours(80)).plusMillis(250).toString();
        CarriedState reset = new CarriedState(
                CALL_SITE, ToolErrorDetector.State.EMPTY, null, null, CONFIG.stateEpoch(), null, null, resetAt);
        series(s, 81, 15, 0.25);
        Sweep after = sweep(s, Map.of(CALL_SITE, reset));

        assertTrue(after.spells().isEmpty(), "the closed spell is not re-accumulated");
        ToolErrorRate relearned = baselineOf(after);
        assertEquals(300, relearned.calls(), "learned from post-reset traffic only, and still learning");
        assertEquals(75, relearned.failures(), "at its 25%");
        assertEquals(resetAt, after.advanced().get(0).resetAt());
    }

    /**
     * The engine recovers a run's failures from its accumulator, which assumes one reference for the whole run.
     * A run that began while the reference was learning was judged against several, so the finding counts its
     * flagged traces instead and derives the rate from the count.
     */
    @Test
    void aRunThatBeganWhileLearningCountsItsFlaggedTraces() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        series(s, 10, 6, 0.40);
        Spell spell = rising(sweep(s)).get(0);
        ToolErrorDetector.Decision derived = spell.decision();
        assertTrue(derived.failuresSinceOnset() != 48, "the derivation is off while the reference learned");

        ToolErrorDetector.Decision counted = GroundednessRateService.counted(derived, 48);

        assertEquals(48, counted.failuresSinceOnset());
        assertEquals(derived.callsSinceOnset(), counted.callsSinceOnset());
        assertEquals(48.0 / derived.callsSinceOnset(), counted.currentRate(), 1e-12);
        assertEquals((counted.currentRate() - derived.baselineRate()) * 100.0, counted.deltaPp(), 1e-9);
        assertEquals(derived.statistic(), counted.statistic());
        assertEquals(derived.onsetAt(), counted.onsetAt());
    }

    // ---- the service over the replay: only a rise is reported, tuning changes reset, unassigned never judged

    @Test
    void onlyARiseIsReported() {
        List<HourlyToolTally> falling = series(new ArrayList<>(), 0, 50, 0.20);
        series(falling, 50, 80, 0.0);
        assertEquals(Direction.DOWN, sweep(falling).spells().get(0).decision().direction(), "the engine sees the drop");

        assertTrue(new Harness(falling, List.of()).refresh().isEmpty(), "fewer flagged answers is not a finding");

        List<HourlyToolTally> rise = series(new ArrayList<>(), 0, 50, 0.05);
        series(rise, 50, 30, 0.25);
        List<Spell> spells = new Harness(rise, List.of()).refresh();
        assertEquals(1, spells.size());
        assertEquals(Direction.UP, spells.get(0).decision().direction());
    }

    @Test
    void tracesWithNoCallSiteAreNeverJudged() {
        List<HourlyToolTally> unassigned = new ArrayList<>();
        for (HourlyToolTally t : series(new ArrayList<>(), 0, 60, 0.50)) {
            unassigned.add(
                    new HourlyToolTally(t.bucket(), GroundednessRateRepository.UNASSIGNED, t.calls(), t.failures()));
        }
        Harness h = new Harness(unassigned, List.of());
        assertTrue(h.refresh().isEmpty());
        verify(h.states, never()).save(anyString(), any(), anyString());
    }

    @Test
    void aStateBuiltUnderOtherTuningIsResetAndReLearned() {
        ToolErrorRate stale = new ToolErrorRate();
        stale.addCounts(200, 10);
        CarriedState old = new CarriedState(
                CALL_SITE,
                new ToolErrorDetector.State(3.0, 0, hour(5), null, 40, 0),
                stale,
                hour(9),
                "v2-onset|groundedness|4.00|gnd-v1-000000000000|50000|2.0|0.02|0.01",
                null,
                null,
                null);
        Harness h = new Harness(series(new ArrayList<>(), 0, 10, 0.05), List.of(old));
        h.refresh();

        verify(h.states).resetAndRelearn("p1", CALL_SITE, null, GroundednessRateService.TUNING_CHANGED, h.now);
        verify(h.states)
                .save(
                        eq("p1"),
                        eq(new CarriedState(
                                CALL_SITE,
                                ToolErrorDetector.State.EMPTY,
                                null,
                                null,
                                CONFIG.stateEpoch(),
                                null,
                                null,
                                h.now)),
                        eq(h.now));
    }

    @Test
    void aStateUnderTheCurrentTuningIsRebuiltNotReset() {
        Sweep learned = sweep(series(new ArrayList<>(), 0, 12, 0.05));
        Harness h = new Harness(series(new ArrayList<>(), 0, 12, 0.05), learned.advanced());
        h.refresh();
        verify(h.states, never()).resetAndRelearn(anyString(), anyString(), any(), anyString(), anyString());
    }

    /** The service over mocked repositories, answering {@code tallies} for any window. */
    private static final class Harness {
        final GroundednessRateRepository rates = mock(GroundednessRateRepository.class);
        final ToolErrorStateRepository states = mock(ToolErrorStateRepository.class);
        final Instant at = START.plus(Duration.ofDays(8));
        final String now = at.toString();
        private final GroundednessRateService service = new GroundednessRateService(
                rates, mock(FindingRepository.class), mock(FindingEvidenceRepository.class), new ObjectMapper());

        Harness(List<HourlyToolTally> tallies, List<CarriedState> carried) {
            @Nullable
            String newest =
                    tallies.isEmpty() ? null : tallies.get(tallies.size() - 1).bucket();
            when(rates.newestObservationAt(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.ofNullable(newest).map(Instant::parse));
            when(rates.hourlyTallies(anyString(), anyString(), anyString(), any()))
                    .thenReturn(tallies);
            when(rates.states()).thenReturn(states);
            when(states.list("p1")).thenReturn(carried);
        }

        List<Spell> refresh() {
            return service.refresh("p1", signal(), at);
        }
    }

    private static ClassifierRow signal() {
        return new ClassifierRow(
                "sig-1",
                "p1",
                "groundedness",
                "Groundedness",
                null,
                BuiltInDetector.Kind.GROUNDEDNESS,
                null,
                true,
                7,
                true,
                ClassifierRow.Mode.TRACKING,
                "2026-09-01T00:00:00Z",
                "2026-09-01T00:00:00Z");
    }
}

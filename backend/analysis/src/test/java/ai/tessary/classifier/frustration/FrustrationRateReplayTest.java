// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

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
 * The rate test over frustration-shaped tallies: a conversation is a trial, a frustrated one a failure, and the
 * shared engine runs on {@link FrustrationConfig}'s defaults (a 200-conversation reference, a floor of 4 on
 * {@code h}, a 10,000-conversation false-alarm budget).
 */
class FrustrationRateReplayTest {

    private static final FrustrationConfig CONFIG = FrustrationConfig.defaults();
    private static final String CALL_SITE = "cs-support-chat";
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final int PER_HOUR = 20;

    private static String hour(int h) {
        return START.plus(Duration.ofHours(h)).toString();
    }

    /** {@code hours} buckets of {@link #PER_HOUR} conversations frustrated at {@code rate}, carrying remainders. */
    private static List<HourlyToolTally> series(List<HourlyToolTally> into, int fromHour, int hours, double rate) {
        double owed = 0;
        for (int h = 0; h < hours; h++) {
            owed += PER_HOUR * rate;
            long frustrated = (long) Math.floor(owed + 1e-9);
            owed -= frustrated;
            into.add(new HourlyToolTally(hour(fromHour + h), CALL_SITE, PER_HOUR, frustrated));
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

    @Test
    void theReferenceIsTheLeading200ConversationsThenFrozen() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 40, 0.05);
        Sweep first = sweep(s);
        ToolErrorRate learned = baselineOf(first);
        assertEquals(200, learned.calls());
        assertEquals(10, learned.failures());

        // The window slides past the hours it learned from and the traffic worsens; a rebuild keeps the reference.
        List<HourlyToolTally> slid = new ArrayList<>(s.subList(20, 40));
        series(slid, 40, 20, 0.30);
        Sweep second = sweep(slid, Map.of(CALL_SITE, first.advanced().get(0).rebuilding()));
        assertEquals(200, baselineOf(second).calls());
        assertEquals(10, baselineOf(second).failures());
        assertEquals(CONFIG.stateEpoch(), second.advanced().get(0).stateEpoch());
    }

    @Test
    void aCallSiteStillLearningIsNotJudged() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 9, 0.50);
        Sweep sweep = sweep(s);
        assertTrue(sweep.advanced().isEmpty(), "180 conversations is still learning");
        assertTrue(sweep.spells().isEmpty());
    }

    @Test
    void aDoublingFromFivePercentAlarmsWithinAFewHundredConversations() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        int hours = 0;
        while (sweep(s).spells().isEmpty()) {
            series(s, 10 + hours, 1, 0.10);
            hours++;
            assertTrue(hours < 40, "a doubling is caught long before 800 conversations");
        }
        int conversations = hours * PER_HOUR;
        assertTrue(conversations <= 300, "caught after " + conversations + " conversations");
        Spell spell = sweep(s).spells().get(0);
        assertEquals(Direction.UP, spell.decision().direction());
        assertEquals(CALL_SITE, spell.toolKey());
    }

    @Test
    void aSteadyFivePercentIsSilentOver5000Conversations() {
        // A seeded run, not a bound: at a 10,000-conversation budget some seeds do alarm within 5,000.
        Random random = new Random(20260922L);
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        for (int h = 10; h < 10 + 250; h++) {
            long frustrated = 0;
            for (int c = 0; c < PER_HOUR; c++) if (random.nextDouble() < 0.05) frustrated++;
            s.add(new HourlyToolTally(hour(h), CALL_SITE, PER_HOUR, frustrated));
            List<Spell> up = sweep(s).spells().stream()
                    .filter(sp -> sp.decision().direction() == Direction.UP)
                    .toList();
            assertTrue(up.isEmpty(), "a calm call site alarmed at hour " + h);
        }
    }

    @Test
    void aSlowRampIsCaught() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        for (int h = 0; h < 100; h++) series(s, 10 + h, 1, 0.05 + 0.10 * h / 100.0);
        List<Spell> spells = sweep(s).spells();
        assertEquals(1, spells.size());
        assertEquals(Direction.UP, spells.get(0).decision().direction());
    }

    @Test
    void aLateFlagReReadIntoItsOriginalHourMovesTheAccumulatorOnTheNextPass() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        series(s, 10, 6, 0.10);
        Sweep first = sweep(s);
        double before = first.advanced().get(0).state().sUp();

        // A later turn of a conversation first scored at hour 12 was flagged; the replay reads it there.
        List<HourlyToolTally> reread = new ArrayList<>(s);
        HourlyToolTally h12 = reread.get(12);
        reread.set(12, new HourlyToolTally(h12.bucket(), CALL_SITE, h12.calls(), h12.failures() + 2));
        Sweep second = sweep(reread, Map.of(CALL_SITE, first.advanced().get(0).rebuilding()));

        assertTrue(second.advanced().get(0).state().sUp() > before);
        assertEquals(baselineOf(first).calls(), baselineOf(second).calls(), "the frozen reference does not move");
    }

    @Test
    void aResetFencesTheHoursBeforeItAndTheReferenceIsReLearnedAfterIt() {
        List<HourlyToolTally> s = series(new ArrayList<>(), 0, 10, 0.05);
        series(s, 10, 30, 0.25);
        Sweep alarming = sweep(s);
        assertEquals(1, alarming.spells().size());

        // resetAndRelearn: accumulator, onset and reference cleared, reset_at written.
        String resetAt = START.plus(Duration.ofHours(40)).plusMillis(250).toString();
        CarriedState reset = new CarriedState(
                CALL_SITE, ToolErrorDetector.State.EMPTY, null, null, CONFIG.stateEpoch(), null, null, resetAt);
        series(s, 41, 15, 0.25);
        Sweep after = sweep(s, Map.of(CALL_SITE, reset));

        assertTrue(after.spells().isEmpty(), "the closed spell is not re-accumulated");
        ToolErrorRate relearned = baselineOf(after);
        assertEquals(200, relearned.calls());
        assertEquals(50, relearned.failures(), "learned from post-reset traffic only, at its 25%");
        assertEquals(resetAt, after.advanced().get(0).resetAt());
    }

    // ---- the service over the replay: only a rise is reported, tuning changes reset, unassigned never judged

    @Test
    void onlyARiseIsReported() {
        List<HourlyToolTally> falling = new ArrayList<>();
        for (HourlyToolTally t : series(new ArrayList<>(), 0, 10, 0.20)) falling.add(t);
        series(falling, 10, 40, 0.0);
        assertEquals(Direction.DOWN, sweep(falling).spells().get(0).decision().direction(), "the engine sees the drop");

        Harness h = new Harness(falling, List.of());
        assertTrue(h.refresh().isEmpty(), "fewer frustrated conversations is not a case");

        List<HourlyToolTally> rising = series(new ArrayList<>(), 0, 10, 0.05);
        series(rising, 10, 30, 0.25);
        List<Spell> spells = new Harness(rising, List.of()).refresh();
        assertEquals(1, spells.size());
        assertEquals(Direction.UP, spells.get(0).decision().direction());
    }

    @Test
    void conversationsWithNoCallSiteAreNeverJudged() {
        List<HourlyToolTally> unassigned = new ArrayList<>();
        for (HourlyToolTally t : series(new ArrayList<>(), 0, 60, 0.50)) {
            unassigned.add(
                    new HourlyToolTally(t.bucket(), FrustrationRateRepository.UNASSIGNED, t.calls(), t.failures()));
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
                "v2-onset|frustration|4.00|jev-choice3-000000000000|10000|2.0|0.02|0.01",
                null,
                null,
                null);
        Harness h = new Harness(series(new ArrayList<>(), 0, 10, 0.05), List.of(old));
        h.refresh();

        verify(h.states).resetAndRelearn("p1", CALL_SITE, null, FrustrationRateService.TUNING_CHANGED, h.now);
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
        final FrustrationRateRepository rates = mock(FrustrationRateRepository.class);
        final ToolErrorStateRepository states = mock(ToolErrorStateRepository.class);
        final Instant at = START.plus(Duration.ofDays(3));
        final String now = at.toString();
        private final FrustrationRateService service = new FrustrationRateService(rates, new ObjectMapper());

        Harness(List<HourlyToolTally> tallies, List<CarriedState> carried) {
            @Nullable
            String newest =
                    tallies.isEmpty() ? null : tallies.get(tallies.size() - 1).bucket();
            when(rates.newestTurnAt(anyString(), anyString(), anyString()))
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
                "frustration",
                "Frustration",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true,
                9,
                true,
                ClassifierRow.Mode.TRACKING,
                "2026-08-01T00:00:00Z",
                "2026-08-01T00:00:00Z");
    }
}

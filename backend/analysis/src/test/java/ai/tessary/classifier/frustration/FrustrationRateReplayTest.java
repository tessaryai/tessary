// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
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
import org.springframework.transaction.support.TransactionOperations;

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

    /** The service over mocked repositories, answering {@code tallies} for any window. */
    private static final class Harness {
        final FrustrationRateRepository rates = mock(FrustrationRateRepository.class);
        final ToolErrorStateRepository states = mock(ToolErrorStateRepository.class);
        final Instant at = START.plus(Duration.ofDays(3));
        final String now = at.toString();
        private final FrustrationRateService service = new FrustrationRateService(
                rates,
                mock(FindingRepository.class),
                mock(FindingEvidenceRepository.class),
                mock(CaseOpener.class),
                TransactionOperations.withoutTransaction(),
                new ObjectMapper());

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

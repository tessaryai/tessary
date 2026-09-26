// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.FlaggedAnswer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rate test over groundedness tallies: a trace is a trial and a flagged answer a failure, on {@link
 * GroundednessConfig}'s defaults (judged from 200 traces, learning until 1,000, {@code h} floor 4, one false alarm
 * per 50,000 traces). Two flags in one trace counting once is the tally query's, held in {@code
 * GroundednessRateIntegrationTest}.
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

    /**
     * The engine assumes one reference per run. A run that began while learning was judged against several, so the
     * finding counts its flagged traces (never more than scored; none reads at baseline). Statistic, threshold, and
     * onset stay the engine's. Expected values by hand, for a 2% baseline.
     */
    @ParameterizedTest(name = "{0} calls, {1} flagged -> {2} failures at {3}")
    @CsvSource({
        "100,  48,  48, 0.48, 46.0, 1.24699",
        "100, 150, 100,  1.0, 98.0, 2.85780",
        "  0,  48,   0, 0.02,  0.0, 0.0",
    })
    void aCountedRunTakesItsRateFromItsFlaggedTraces(
            long calls, long flagged, long failures, double rate, double deltaPp, double effectSize) {
        ToolErrorDetector.Decision derived = new ToolErrorDetector.Decision(
                true, Direction.UP, 14.1, 11.2, 1.26, 0.02, 0.30, 28.0, 0.62, calls, 30, 640, hour(10), null);

        ToolErrorDetector.Decision counted = GroundednessRateService.counted(derived, flagged);

        assertEquals(failures, counted.failuresSinceOnset());
        assertEquals(calls, counted.callsSinceOnset());
        assertEquals(rate, counted.currentRate(), 1e-12);
        assertEquals(deltaPp, counted.deltaPp(), 1e-9);
        assertEquals(effectSize, counted.effectSize(), 1e-5);
        assertEquals(
                List.of(true, Direction.UP, 14.1, 11.2, 1.26, 0.02, 640L, hour(10)),
                List.of(
                        counted.fired(),
                        counted.direction(),
                        counted.statistic(),
                        counted.threshold(),
                        counted.criticality(),
                        counted.baselineRate(),
                        counted.baselineCalls(),
                        String.valueOf(counted.onsetAt())));
    }

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
        assertEquals(List.of(), h.states.saves);
    }

    /** A retune clears the accumulator and the reference, and keeps a person's pending pin. */
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
                "usr_pinner",
                hour(8),
                null);
        Harness h = new Harness(series(new ArrayList<>(), 0, 10, 0.05), List.of(old));
        h.refresh();

        assertEquals(
                List.of(new Reset("p1", CALL_SITE, null, GroundednessRateService.TUNING_CHANGED, h.now)),
                h.states.resets);
        assertEquals(
                new Save(
                        "p1",
                        new CarriedState(
                                CALL_SITE,
                                ToolErrorDetector.State.EMPTY,
                                null,
                                null,
                                CONFIG.stateEpoch(),
                                "usr_pinner",
                                hour(8),
                                h.now),
                        h.now),
                h.states.saves.getFirst(),
                "the retuned row, saved before the replay advances it");
    }

    private record Reset(
            String projectId, String key, @Nullable String resetBy, String note, String resetAt) {}

    private record Save(String projectId, CarriedState carried, String updatedAt) {}

    /** The call sites' carried state: listed as given, every save and reset recorded. */
    private static final class RecordedStates extends ToolErrorStateRepository {
        private final List<CarriedState> carried;
        final List<Save> saves = new ArrayList<>();
        final List<Reset> resets = new ArrayList<>();

        RecordedStates(List<CarriedState> carried) {
            super(mock(JdbcClient.class));
            this.carried = carried;
        }

        @Override
        public List<CarriedState> list(String projectId) {
            assertEquals("p1", projectId);
            return carried;
        }

        @Override
        public void save(String projectId, CarriedState state, String updatedAt) {
            saves.add(new Save(projectId, state, updatedAt));
        }

        @Override
        public void resetAndRelearn(
                String projectId, String key, @Nullable String resetBy, String note, String resetAt) {
            resets.add(new Reset(projectId, key, resetBy, note, resetAt));
        }
    }

    /** The scored hours: {@code tallies} for any window, and no trace behind a spell to list. */
    private static final class ScoredHours extends GroundednessRateRepository {
        private final List<HourlyToolTally> tallies;
        private final RecordedStates states;

        ScoredHours(List<HourlyToolTally> tallies, RecordedStates states) {
            super(mock(JdbcClient.class), mock(ClassifierDetectionWriteRepository.class));
            this.tallies = tallies;
            this.states = states;
        }

        @Override
        public Optional<Instant> newestObservationAt(String projectId, String classifierId, String scorerVersion) {
            return tallies.isEmpty()
                    ? Optional.empty()
                    : Optional.of(Instant.parse(tallies.getLast().bucket()));
        }

        @Override
        public List<HourlyToolTally> hourlyTallies(
                String projectId, String classifierId, String scorerVersion, Instant from) {
            return tallies;
        }

        @Override
        public ToolErrorStateRepository states() {
            return states;
        }

        @Override
        public List<String> scoredSince(
                String projectId,
                String classifierId,
                String scorerVersion,
                String callSiteId,
                Instant windowFrom,
                Instant since,
                Instant until) {
            return List.of();
        }

        @Override
        public List<FlaggedAnswer> flaggedSince(
                String projectId,
                String classifierId,
                String scorerVersion,
                String callSiteId,
                Instant windowFrom,
                Instant since,
                Instant until) {
            return List.of();
        }
    }

    /** Fake rate and state repositories; the finding write records nothing. */
    private static final class Harness {
        final RecordedStates states;
        final Instant at = START.plus(Duration.ofDays(8));
        final String now = at.toString();
        private final GroundednessRateService service;

        Harness(List<HourlyToolTally> tallies, List<CarriedState> carried) {
            states = new RecordedStates(carried);
            service = new GroundednessRateService(
                    new ScoredHours(tallies, states),
                    mock(FindingRepository.class),
                    mock(FindingEvidenceRepository.class),
                    new ObjectMapper());
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

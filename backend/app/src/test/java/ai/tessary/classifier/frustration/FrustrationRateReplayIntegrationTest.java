// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.RateClassifierFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rate test against Postgres: the replay aggregate over {@code frustration_assessment} and the uncleared
 * flags, the state row it leaves in {@code frustration_state}, and a tuning change resetting it.
 */
@SpringBootTest
record FrustrationRateReplayIntegrationTest(
        FrustrationRateRepository rates,
        FrustrationRateService service,
        ClassifierRepository classifiers,
        JdbcClient jdbc,
        RateClassifierFixture fixture) {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    /**
     * A session is a conversation on one call site. Keyed on the conversation alone, conv-a counts once, on cs-a
     * where it was first scored, and its flag on cs-b makes cs-a fail although nothing on cs-a frustrated anyone.
     */
    @Test
    void aSessionIsOneTrialPerCallSiteInItsFirstHourThereAndFailsOnlyWhileFlagged() {
        String pid = fixture.project("fr-tally", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant h10 = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        // Calm on cs-a, then flagged on a later turn on cs-b: a calm trial on cs-a and a failure on cs-b, each in
        // its own first hour.
        fixture.frustrationAssessment(
                pid, signal, "a1", "conv-a", "cs-a", h10.plus(Duration.ofMinutes(5)), false, VERSION);
        fixture.frustrationAssessment(
                pid, signal, "a2", "conv-a", "cs-b", h10.plus(Duration.ofHours(2)), true, VERSION);
        flag(pid, signal, "a2", "conv-a", "cs-b", false);
        // Flagged, then cleared by a false-alarm resolve: a trial, not a failure.
        fixture.frustrationAssessment(
                pid, signal, "b1", "conv-b", "cs-a", h10.plus(Duration.ofMinutes(30)), true, VERSION);
        flag(pid, signal, "b1", "conv-b", "cs-a", true);
        fixture.frustrationAssessment(
                pid, signal, "c1", "conv-c", "cs-b", h10.plus(Duration.ofMinutes(70)), false, VERSION);
        fixture.frustrationAssessment(
                pid, signal, "d1", "conv-d", null, h10.plus(Duration.ofMinutes(40)), false, VERSION);
        // Another scorer's rows and rows before the window do not count.
        fixture.frustrationAssessment(
                pid, signal, "e1", "conv-e", "cs-a", h10.plus(Duration.ofMinutes(10)), true, "jev-choice3-other");
        fixture.frustrationAssessment(
                pid, signal, "f1", "conv-f", "cs-a", h10.minus(Duration.ofDays(1)), false, VERSION);

        List<HourlyToolTally> tallies = rates.hourlyTallies(pid, signal.id(), VERSION, h10);

        assertEquals(
                List.of(
                        new HourlyToolTally(h10.toString(), FrustrationRateRepository.UNASSIGNED, 1, 0),
                        new HourlyToolTally(h10.toString(), "cs-a", 2, 0),
                        new HourlyToolTally(h10.plus(Duration.ofHours(1)).toString(), "cs-b", 1, 0),
                        new HourlyToolTally(h10.plus(Duration.ofHours(2)).toString(), "cs-b", 1, 1)),
                tallies);
        assertEquals(
                h10.plus(Duration.ofHours(2)),
                rates.newestTurnAt(pid, signal.id(), VERSION).orElseThrow());
        assertTrue(rates.newestTurnAt(pid, signal.id(), "jev-choice3-none").isEmpty());
    }

    @Test
    void aRiseAlarmsAndTheStateRowIsSaved() {
        String pid = fixture.project("fr-rise", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        // Judged from 100 (four hours), and every later settled hour is added to the reference until 1,000. With the
        // rise ending at hour 36, hours up to 11 have settled: a day after they ended.
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05); // 210 conversations at 5%
        seedHours(pid, signal, "cs-chat", start, 31, 6, 30, 0.40); // then 180 at 40%
        seedHours(pid, signal, "cs-quiet", start, 0, 2, 10, 0.0); // 20 conversations: still learning
        fixture.frustrationAssessment(pid, signal, "orphan", "conv-orphan", null, start, false, VERSION);

        List<Spell> spells = service.refresh(pid, signal, Instant.now());

        assertEquals(1, spells.size());
        assertEquals("cs-chat", spells.get(0).toolKey());
        Map<String, CarriedState> states = rates.states().byTool(pid);
        assertEquals(List.of("cs-chat"), List.copyOf(states.keySet()), "a learning call site carries no row");
        CarriedState chat = state(pid, "cs-chat");
        ToolErrorRate baseline = chat.baseline();
        assertNotNull(baseline);
        assertEquals(210, baseline.calls(), "only the settled hours, not the rise");
        assertEquals(
                FrustrationConfig.of(new ObjectMapper(), signal.configJson()).stateEpoch(), chat.stateEpoch());
    }

    @Test
    void aTuningChangeResetsTheCallSiteAndItReLearnsFromLaterTraffic() {
        String pid = fixture.project("fr-retune", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 12, 30, 0.05);
        // A later hour on the call site's own clock, so hours 0-11 have settled and a reference is learned.
        seedHours(pid, signal, "cs-chat", start, 40, 1, 30, 0.05);
        service.refresh(pid, signal, Instant.now());
        assertNotNull(state(pid, "cs-chat").baseline());

        classifiers.updateConfig(pid, signal.id(), "{\"arl_target\":20000}");
        ClassifierRow retuned =
                ClassifierRows.byKey(classifiers, pid, "frustration").orElseThrow();
        Instant at = Instant.now();
        service.refresh(pid, retuned, at);

        CarriedState state = state(pid, "cs-chat");
        assertNull(state.baseline(), "the old reference is dropped");
        assertEquals(at.toString(), state.resetAt());
        assertEquals(
                FrustrationConfig.of(new ObjectMapper(), "{\"arl_target\":20000}")
                        .stateEpoch(),
                state.stateEpoch());

        service.refresh(pid, retuned, Instant.now());
        assertEquals(
                at.toString(),
                state(pid, "cs-chat").resetAt(),
                "a second pass under the same tuning does not move the fence");
    }

    // ---- fixtures

    private CarriedState state(String pid, String callSite) {
        CarriedState state = rates.states().byTool(pid).get(callSite);
        assertNotNull(state, callSite + " has a state row");
        return state;
    }

    /** {@code perHour} one-turn conversations an hour, the first {@code rate} of each hour flagged. */
    private void seedHours(
            String pid,
            ClassifierRow signal,
            String callSite,
            Instant start,
            int fromHour,
            int hours,
            int perHour,
            double rate) {
        long flaggedPerHour = Math.round(perHour * rate);
        for (int h = fromHour; h < fromHour + hours; h++) {
            for (int c = 0; c < perHour; c++) {
                String id = callSite + "-" + h + "-" + c;
                boolean flagged = c < flaggedPerHour;
                fixture.frustrationAssessment(
                        pid,
                        signal,
                        id,
                        "conv-" + id,
                        callSite,
                        start.plus(Duration.ofHours(h)).plusSeconds(c),
                        flagged,
                        VERSION);
                if (flagged) flag(pid, signal, id, "conv-" + id, callSite, false);
            }
        }
    }

    private void flag(
            String pid, ClassifierRow signal, String traceId, String conversation, String callSite, boolean cleared) {
        jdbc.sql("INSERT INTO " + "frustration_detection"
                        + " (id, project_id, classifier_id, classifier_key, subject_session_id, subject_trace_id,"
                        + " severity, confidence, evidence, cleared_at)"
                        + " VALUES (:id, :pid, :cid, 'frustration', :conv, :trace, 'warn', 'high',"
                        + " jsonb_build_object('call_site_id', CAST(:callSite AS text)), :cleared)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("conv", conversation)
                .param("trace", traceId)
                .param("callSite", callSite)
                .param("cleared", cleared ? Instant.now().toString() : null)
                .update();
    }
}

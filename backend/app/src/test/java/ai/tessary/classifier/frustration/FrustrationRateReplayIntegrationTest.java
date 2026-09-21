// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierDtos.FrustrationCallSiteView;
import ai.tessary.classifier.ClassifierDtos.FrustrationTuningView;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rate test against Postgres: the replay aggregate over {@code frustration_assessment} and the uncleared
 * flags, the state row it leaves in {@code frustration_state}, a tuning change resetting it, and the Tuning view
 * reading it back.
 *
 * <p>Shares the turn-grain fingerprint, whose test table stands in for {@code frustration_detection}.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class FrustrationRateReplayIntegrationTest {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    @Autowired
    FrustrationAssessmentRepository assessments;

    @Autowired
    FrustrationRateRepository rates;

    @Autowired
    FrustrationRateService service;

    @Autowired
    FrustrationTuning tuning;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Test
    void aConversationIsOneTrialOnItsFirstScoredCallSiteAndHourAndFailsOnlyWhileFlagged() {
        String pid = project("fr-tally");
        ClassifierRow signal = frustration(pid);
        Instant h10 = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        // Calm on cs-a, then flagged on a later turn on cs-b: a failure on cs-a, in its first hour.
        assess(pid, signal, "a1", "conv-a", "cs-a", h10.plus(Duration.ofMinutes(5)), false, VERSION);
        assess(pid, signal, "a2", "conv-a", "cs-b", h10.plus(Duration.ofHours(2)), true, VERSION);
        flag(pid, signal, "a2", "conv-a", false);
        // Flagged, then cleared by a false-alarm resolve: a trial, not a failure.
        assess(pid, signal, "b1", "conv-b", "cs-a", h10.plus(Duration.ofMinutes(30)), true, VERSION);
        flag(pid, signal, "b1", "conv-b", true);
        assess(pid, signal, "c1", "conv-c", "cs-b", h10.plus(Duration.ofMinutes(70)), false, VERSION);
        assess(pid, signal, "d1", "conv-d", null, h10.plus(Duration.ofMinutes(40)), false, VERSION);
        // Another scorer's rows and rows before the window do not count.
        assess(pid, signal, "e1", "conv-e", "cs-a", h10.plus(Duration.ofMinutes(10)), true, "jev-choice3-other");
        assess(pid, signal, "f1", "conv-f", "cs-a", h10.minus(Duration.ofDays(1)), false, VERSION);

        List<HourlyToolTally> tallies = rates.hourlyTallies(pid, signal.id(), VERSION, h10);

        assertEquals(
                List.of(
                        new HourlyToolTally(h10.toString(), FrustrationRateRepository.UNASSIGNED, 1, 0),
                        new HourlyToolTally(h10.toString(), "cs-a", 2, 1),
                        new HourlyToolTally(h10.plus(Duration.ofHours(1)).toString(), "cs-b", 1, 0)),
                tallies);
        assertEquals(
                h10.plus(Duration.ofHours(2)),
                rates.newestTurnAt(pid, signal.id(), VERSION).orElseThrow());
        assertTrue(rates.newestTurnAt(pid, signal.id(), "jev-choice3-none").isEmpty());
    }

    @Test
    void aRiseAlarmsTheStateRowIsSavedAndTheTuningViewReadsIt() {
        String pid = project("fr-rise");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05); // 210 conversations of reference at 5%
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40); // then 180 at 40%
        seedHours(pid, signal, "cs-quiet", start, 0, 2, 10, 0.0); // 20 conversations: still learning
        assess(pid, signal, "orphan", "conv-orphan", null, start, false, VERSION);

        List<Spell> spells = service.refresh(pid, signal, Instant.now());

        assertEquals(1, spells.size());
        assertEquals("cs-chat", spells.get(0).toolKey());
        Map<String, CarriedState> states = rates.states().byTool(pid);
        assertEquals(List.of("cs-chat"), List.copyOf(states.keySet()), "a learning call site carries no row");
        CarriedState chat = state(pid, "cs-chat");
        ToolErrorRate baseline = chat.baseline();
        assertNotNull(baseline);
        assertEquals(210, baseline.calls());
        assertEquals(FrustrationConfig.defaults().stateEpoch(), chat.stateEpoch());

        FrustrationTuningView view = tuning.view(pid, signal);
        assertEquals(0.40, view.threshold());
        assertEquals(10_000L, view.arlTarget());
        assertEquals(4.0, view.minDecisionInterval());
        assertEquals(200, view.minBaselineConversations());
        assertEquals(VERSION, view.scorerVersion());
        assertEquals(1, view.unassignedConversations());
        assertEquals(2, view.callSites().size());
        FrustrationCallSiteView alarming = view.callSites().get(0);
        assertEquals("cs-chat", alarming.callSiteId());
        assertEquals(FrustrationCallSiteView.ALARMING, alarming.state());
        assertEquals(210L, alarming.baselineConversations());
        assertNotNull(alarming.baselineRate());
        assertNotNull(alarming.decisionInterval());
        assertTrue(alarming.statistic() >= alarming.decisionInterval());
        assertNotNull(alarming.onsetAt());
        FrustrationCallSiteView learning = view.callSites().get(1);
        assertEquals("cs-quiet", learning.callSiteId());
        assertEquals(FrustrationCallSiteView.LEARNING, learning.state());
        assertEquals(20, learning.learnedConversations());
        assertNull(learning.baselineRate());
    }

    @Test
    void aTuningChangeResetsTheCallSiteAndItReLearnsFromLaterTraffic() {
        String pid = project("fr-retune");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 12, 30, 0.05);
        service.refresh(pid, signal, Instant.now());
        assertNotNull(state(pid, "cs-chat").baseline());

        classifiers.updateConfig(pid, signal.id(), "{\"arl_target\":20000}");
        ClassifierRow retuned = classifiers.findByKey(pid, "frustration").orElseThrow();
        Instant at = Instant.now();
        service.refresh(pid, retuned, at);

        CarriedState state = state(pid, "cs-chat");
        assertNull(state.baseline(), "the old reference is dropped");
        assertEquals(at.toString(), state.resetAt());
        assertEquals(
                FrustrationConfig.of(new ObjectMapper(), "{\"arl_target\":20000}")
                        .stateEpoch(),
                state.stateEpoch());
        FrustrationCallSiteView view = tuning.view(pid, retuned).callSites().get(0);
        assertEquals(FrustrationCallSiteView.LEARNING, view.state());
        assertEquals(0, view.learnedConversations(), "every hour so far is before the reset");
        assertEquals(FrustrationRateService.TUNING_CHANGED, view.resetNote());

        service.refresh(pid, retuned, Instant.now());
        assertEquals(
                at.toString(),
                state(pid, "cs-chat").resetAt(),
                "a second pass under the same tuning does not move the fence");
    }

    @Test
    void theTuningViewRefusesAnyOtherClassifier() {
        String pid = project("fr-other");
        classifierService.seedBuiltIns(pid);
        ClassifierRow other = classifierService.list(pid).stream()
                .filter(c -> !"frustration".equals(c.classifierKey()))
                .findFirst()
                .orElseThrow();
        assertThrows(TessaryException.class, () -> tuning.view(pid, other));
    }

    // ---- fixtures

    private CarriedState state(String pid, String callSite) {
        CarriedState state = rates.states().byTool(pid).get(callSite);
        assertNotNull(state, callSite + " has a state row");
        return state;
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
    }

    private ClassifierRow frustration(String pid) {
        classifierService.seedBuiltIns(pid);
        return classifiers.findByKey(pid, "frustration").orElseThrow();
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
                assess(
                        pid,
                        signal,
                        id,
                        "conv-" + id,
                        callSite,
                        start.plus(Duration.ofHours(h)).plusSeconds(c),
                        flagged,
                        VERSION);
                if (flagged) flag(pid, signal, id, "conv-" + id, false);
            }
        }
    }

    private void assess(
            String pid,
            ClassifierRow signal,
            String traceId,
            String conversation,
            @Nullable String callSite,
            Instant at,
            boolean frustrated,
            String version) {
        assessments.insert(new Assessment(
                Ids.ulid(),
                pid,
                signal.id(),
                traceId,
                "span-" + traceId,
                conversation,
                callSite,
                at,
                frustrated,
                version,
                "TYPESAFE",
                "typesafe/jev-1.13-20260917",
                null,
                "{}",
                null,
                null,
                null));
    }

    private void flag(String pid, ClassifierRow signal, String traceId, String conversation, boolean cleared) {
        jdbc.sql("INSERT INTO " + TurnGrainTestDetectionConfig.TABLE
                        + " (id, project_id, classifier_id, classifier_key, subject_session_id, subject_trace_id,"
                        + " severity, confidence, cleared_at)"
                        + " VALUES (:id, :pid, :cid, 'frustration', :conv, :trace, 'warn', 'high', :cleared)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("conv", conversation)
                .param("trace", traceId)
                .param("cleared", cleared ? Instant.now().toString() : null)
                .update();
    }
}

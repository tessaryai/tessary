// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static ai.tessary.testsupport.ScriptedHttpClient.response;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Backlog;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.ShadowRow;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Summary;
import ai.tessary.testsupport.Props;
import ai.tessary.testsupport.ScriptedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Against a scripted {@link java.net.http.HttpClient}. */
class FrustrationShadowSweepTest {

    private static final String HEALTH = "http://10.2.1.9:8000/health";
    private static final String SCORE = "http://10.2.1.9:8000/v1/systemone";
    private static final String REQUEST =
            "{\"model\":\"~typesafe/jev-latest\",\"state\":{\"current_user_message\":\"again??\",\"earlier_messages\":[]},"
                    + "\"questions\":{\"user_stance\":{\"type\":\"choice\",\"instructions\":\"x\",\"criteria\":{}}}}";
    private static final String ANSWER_HIGH =
            "{\"answers\":{\"user_stance\":{\"type\":\"choice\",\"choice\":\"unhappy_with_assistant\","
                    + "\"probabilities\":{\"unhappy_with_assistant\":0.91,\"unhappy_other_cause\":0.04,\"neutral_or_positive\":0.05}}}}";
    private static final String ANSWER_LOW =
            "{\"answers\":{\"user_stance\":{\"type\":\"choice\",\"choice\":\"neutral_or_positive\","
                    + "\"probabilities\":{\"unhappy_with_assistant\":0.12,\"unhappy_other_cause\":0.03,\"neutral_or_positive\":0.85}}}}";

    private final ScriptedHttpClient http = new ScriptedHttpClient();
    private final FrustrationShadowRepository shadows = mock(FrustrationShadowRepository.class);
    private final ClassifierRepository classifiers = mock(ClassifierRepository.class);
    private final List<ShadowRow> written = new ArrayList<>();

    /** {@code props} override the url and a batch of two. */
    private FrustrationShadowSweep sweep(Object... props) {
        List<Object> keyValues = new ArrayList<>(List.of("url", "http://10.2.1.9:8000/", "batch", 2));
        keyValues.addAll(List.of(props));
        when(shadows.summary()).thenReturn(new Summary(0, 0, 0, 0, 0));
        when(shadows.insert(any())).thenAnswer(inv -> written.add(inv.getArgument(0)));
        when(classifiers.findById(any(), any())).thenReturn(Optional.empty());
        return new FrustrationShadowSweep(
                shadows,
                classifiers,
                Props.of(FrustrationShadowProperties.class, keyValues.toArray()),
                new ObjectMapper(),
                http,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static Backlog turn(String id, boolean referenceFlag) {
        return new Backlog(id, "p1", "c1", "t-" + id, "v1", REQUEST, referenceFlag);
    }

    @Test
    void doesNothingWithoutAUrl() {
        FrustrationShadowSweep s = new FrustrationShadowSweep(
                shadows,
                classifiers,
                Props.of(FrustrationShadowProperties.class),
                new ObjectMapper(),
                http,
                Clock.systemUTC());
        FrustrationShadowSweep.Run run = s.sweep();
        assertEquals(0, run.sent());
        verify(shadows, never()).backlog(anyInt());
    }

    @Test
    void leavesTheBacklogWhileTheServerIsAsleep() {
        FrustrationShadowSweep s = sweep();
        http.on(HEALTH, new ConnectException("refused"));
        FrustrationShadowSweep.Run run = s.sweep();
        assertFalse(run.serverUp());
        verify(shadows, never()).backlog(anyInt());
    }

    @Test
    void drainsTheBacklogInBatchesAndFlagsAgainstTheThreshold() {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2))
                .thenReturn(List.of(turn("a1", true), turn("a2", false)))
                .thenReturn(List.of(turn("a3", true)))
                .thenReturn(List.of());
        http.on(HEALTH, response(200, "{\"ok\":true}"))
                .on(SCORE, response(200, ANSWER_HIGH), response(200, ANSWER_LOW), response(200, ANSWER_LOW));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(3, run.sent());
        assertEquals(3, run.scored());
        assertEquals(0, run.refused());
        assertTrue(run.serverUp());
        assertEquals(3, written.size());
        ShadowRow a1 = written.get(0);
        assertEquals("a1", a1.assessmentId());
        assertEquals(new java.math.BigDecimal("0.91"), a1.score());
        assertEquals(Boolean.TRUE, a1.frustrated()); // 0.91 > the default 0.40
        assertTrue(a1.referenceFrustrated());
        assertEquals("decider", a1.shadowModel());
        ShadowRow a3 = written.get(2);
        assertEquals(Boolean.FALSE, a3.frustrated()); // 0.12 < 0.40: the shadow disagrees with the reference
        assertTrue(a3.referenceFrustrated());
    }

    @Test
    void sendsTheStoredBodyWithOnlyTheModelReplaced() {
        FrustrationShadowSweep s = sweep("model", "eikos", "api-key", "k");
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false))).thenReturn(List.of());
        http.on(HEALTH, response(200, "{}")).on(SCORE, response(200, ANSWER_LOW));

        s.sweep();

        assertEquals(2, http.sent().size());
        HttpRequest post = http.sent().get(1);
        assertEquals(SCORE, post.uri().toString());
        assertEquals(Optional.of("Bearer k"), post.headers().firstValue("Authorization"));
        String body = ScriptedHttpClient.body(post);
        assertTrue(body.contains("\"model\":\"eikos\""), body);
        assertTrue(body.contains("\"current_user_message\":\"again??\""), body);
        assertFalse(body.contains("jev-latest"), body);
    }

    @Test
    void aFourHundredIsRecordedAsARefusalSoTheTurnIsNotResent() {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false))).thenReturn(List.of());
        http.on(HEALTH, response(200, "{}")).on(SCORE, response(400, "{\"error\":{\"message\":\"too many options\"}}"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(1, run.refused());
        assertEquals(0, run.scored());
        assertNull(written.get(0).score());
        assertNull(written.get(0).frustrated());
        assertTrue(written.get(0).responseJson().contains("HTTP 400"));
    }

    /** A gateway's error page in a 2xx has no probability: the turn is refused, not scored or left to crash the run. */
    @Test
    void aTwoHundredWithNoProbabilityIsRecordedAsARefusal() {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", true))).thenReturn(List.of());
        http.on(HEALTH, response(200, "{}")).on(SCORE, response(200, "<html>502 Bad Gateway</html>"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(new FrustrationShadowSweep.Run(1, 0, 1, true, false), run);
        assertNull(written.get(0).score());
        assertNull(written.get(0).frustrated());
    }

    @Test
    void aFiveHundredEndsTheRunWithoutARow() {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false), turn("a2", false)));
        http.on(HEALTH, response(200, "{}")).on(SCORE, response(503, "going down"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertFalse(run.serverUp());
        assertEquals(0, run.sent());
        assertTrue(written.isEmpty());
    }

    /** Interrupted mid-send, the run stops as if the server went away, and the scheduler still sees the interrupt. */
    @Test
    void anInterruptedSendEndsTheRunWithoutARowAndKeepsTheInterrupt() {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false), turn("a2", false)));
        http.on(HEALTH, response(200, "{}")).on(SCORE, new InterruptedException("shutting down"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertTrue(Thread.interrupted(), "the interrupt is kept");
        assertEquals(new FrustrationShadowSweep.Run(0, 0, 0, false, false), run);
        assertTrue(written.isEmpty());
    }

    /** A run past its budget yields before sending more, so the next sweep carries on. */
    @Test
    void aSpentBudgetEndsTheRunBeforeTheNextSend() {
        FrustrationShadowSweep s = sweep("budget-ms", 0);
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false)));
        http.on(HEALTH, response(200, "{}"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(new FrustrationShadowSweep.Run(0, 0, 0, true, true), run);
        assertEquals(
                List.of(HEALTH),
                http.sent().stream().map(r -> r.uri().toString()).toList());
    }

    /** A failing sweep must not escape into the scheduler thread; the next tick tries again. */
    @Test
    void aFailingScheduledSweepIsContained() {
        FrustrationShadowSweep s = sweep();
        http.on(HEALTH, response(200, "{}"));
        when(shadows.backlog(2)).thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(s::scheduled);
    }

    @Test
    void usesTheClassifiersOwnThreshold() {
        FrustrationShadowSweep s = sweep();
        ClassifierRow row = new ClassifierRow(
                "c1",
                "p1",
                "frustration",
                "Frustration",
                null,
                "frustration",
                "{\"threshold\":0.95}",
                true,
                1,
                true,
                "tracking",
                "2026-10-01T00:00:00Z",
                "2026-10-01T00:00:00Z",
                null);
        when(classifiers.findById("p1", "c1")).thenReturn(Optional.of(row));
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", true))).thenReturn(List.of());
        http.on(HEALTH, response(200, "{}")).on(SCORE, response(200, ANSWER_HIGH));

        s.sweep();

        assertEquals(Boolean.FALSE, written.get(0).frustrated()); // 0.91 < 0.95
    }
}

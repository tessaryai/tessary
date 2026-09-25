// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierPause;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.PagedDetector.FiredTurn;
import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.TurnFacts;
import ai.tessary.classifier.frustration.StructuredThread.Message;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.config.FrustrationProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.decisions.DecisionAnswer;
import ai.tessary.llm.decisions.DecisionClient;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionTarget;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The frustration detector's writes against Postgres: an assessment per sent turn and a detection per
 * flagged turn, idempotent when a page is written twice; the conversation flag that stops a
 * conversation being sent again until a human clears it; and the pause and held-page counters the sweep
 * keeps. The decision call and the conversation read are stubbed; everything else is the real schema.
 */
@SpringBootTest
class FrustrationAssessmentIntegrationTest {

    private static final String RESPONDED = "typesafe/jev-1.13-20260917";
    private static final String CURRENT_PREFIX = "still wrong ";

    @Autowired
    FrustrationAssessmentRepository assessments;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    TransactionOperations tx;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    private final ConversationThreadAssembler assembler = mock(ConversationThreadAssembler.class);
    private final DecisionProviderResolver providers = mock(DecisionProviderResolver.class);
    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        when(providers.resolve(any(), any()))
                .thenReturn(Optional.of(new DecisionTarget(
                        ModelProvider.TYPESAFE,
                        "jev-latest",
                        URI.create("https://api.typesafe.ai/v1/systemone"),
                        "k")));
    }

    @Test
    void aPersistedPageWritesEveryColumnOfItsAssessmentAndOneDetectionPerFlaggedTurn() throws Exception {
        String pid = project("fr-write");
        ClassifierRow signal = frustration(pid);
        Instant started = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        SubstrateObservation flagged = turn(pid, "tr-flagged", "sess-1", "thread-1", started);
        SubstrateObservation calm = turn(pid, "tr-calm", "sess-2", null, started);
        JevFrustrationDetector detector = detector(Map.of("tr-flagged", 0.82, "tr-calm", 0.05));

        List<FiredTurn> fired =
                detector.complete(signal, detector.score(signal, List.of(flagged, calm)), PageAction.PERSIST, 7);

        assertEquals(1, fired.size());
        String version = JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);
        Assessment row =
                assessments.find(pid, signal.id(), "tr-flagged", version).orElseThrow();
        assertEquals(pid, row.projectId());
        assertEquals(signal.id(), row.classifierId());
        assertEquals("span-tr-flagged", row.spanId());
        assertEquals("thread-1", row.conversationId(), "the thread wins over the session");
        assertEquals("cs-1", row.callSiteId());
        assertEquals(started, row.turnStartedAt());
        assertTrue(row.frustrated());
        assertEquals(version, row.scorerVersion());
        assertEquals("TYPESAFE", row.provider());
        assertEquals(RESPONDED, row.model());
        assertEquals(800, row.inputTokens());
        assertEquals(0, new BigDecimal("0.00003200").compareTo(row.costUsd()));
        assertEquals(90, row.latencyMs());
        JsonNode request = mapper.readTree(row.requestJson());
        assertEquals(
                CURRENT_PREFIX + "tr-flagged",
                request.path("state").path("current_user_message").asText());
        assertTrue(request.path("questions").has(JevFrustrationQuestion.NAME));
        assertEquals(
                RESPONDED, mapper.readTree(row.responseJson()).path("model").asText());

        Assessment calmRow =
                assessments.find(pid, signal.id(), "tr-calm", version).orElseThrow();
        assertFalse(calmRow.frustrated());
        assertEquals("sess-2", calmRow.conversationId(), "no thread, so the session");

        Map<String, Object> detection = jdbc.sql("SELECT subject_session_id, subject_trace_id, severity, confidence,"
                        + " cleared_at, evidence::text AS evidence FROM " + "frustration_detection"
                        + " WHERE project_id = :pid")
                .param("pid", pid)
                .query()
                .singleRow();
        assertEquals("thread-1", detection.get("subject_session_id"));
        assertEquals("tr-flagged", detection.get("subject_trace_id"));
        assertEquals("warn", detection.get("severity"));
        assertEquals("high", detection.get("confidence"));
        assertNull(detection.get("cleared_at"));
        assertEquals(
                RESPONDED,
                mapper.readTree((String) detection.get("evidence"))
                        .path("model")
                        .asText());
    }

    @Test
    void aPageWrittenTwiceWritesEachRowOnce() {
        String pid = project("fr-idempotent");
        ClassifierRow signal = frustration(pid);
        SubstrateObservation flagged = turn(pid, "tr-1", "sess-1", null, Instant.now());
        JevFrustrationDetector detector = detector(Map.of("tr-1", 0.9));

        detector.complete(signal, detector.score(signal, List.of(flagged)), PageAction.PERSIST, 1);
        List<FiredTurn> again =
                detector.complete(signal, detector.score(signal, List.of(flagged)), PageAction.PERSIST, 1);

        assertTrue(again.isEmpty(), "the re-sent turn is not a new detection");
        assertEquals(1, count("frustration_assessment", pid));
        assertEquals(1, count("frustration_detection", pid));
    }

    @Test
    void aFlaggedConversationIsSuppressedUntilItsFlagIsCleared() {
        String pid = project("fr-suppress");
        ClassifierRow signal = frustration(pid);
        Instant at = Instant.now();
        SubstrateObservation first = turn(pid, "tr-a1", "sess-a", "thread-a", at);
        turn(pid, "tr-a2", "sess-a", "thread-a", at.plusSeconds(60));
        turn(pid, "tr-b1", "sess-b", null, at);
        fx.trace(pid, "tr-none", at);
        JevFrustrationDetector detector = detector(Map.of("tr-a1", 0.9));
        detector.complete(signal, detector.score(signal, List.of(first)), PageAction.PERSIST, 1);

        Set<String> suppressed = detections.tracesInUnclearedFlaggedConversations(
                BuiltInDetector.Kind.FRUSTRATION, pid, signal.id(), List.of("tr-a2", "tr-b1", "tr-none"));
        assertEquals(Set.of("tr-a2"), suppressed, "only the flagged conversation's later turn is dropped");

        jdbc.sql("UPDATE " + "frustration_detection" + " SET cleared_at = :now WHERE project_id = :pid")
                .param("now", Instant.now().toString())
                .param("pid", pid)
                .update();
        assertEquals(
                Set.of(),
                detections.tracesInUnclearedFlaggedConversations(
                        BuiltInDetector.Kind.FRUSTRATION, pid, signal.id(), List.of("tr-a2")),
                "a cleared flag makes the conversation scorable again");
    }

    @Test
    void turnFactsReadTheConversationKeyAndStart() {
        String pid = project("fr-facts");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        fx.trace(pid, "tr-thread", "sess-x", "thread-x", null, at);
        fx.trace(pid, "tr-session", "sess-y", null, null, at);
        fx.trace(pid, "tr-alone", at);

        Map<String, TurnFacts> facts =
                assessments.turnFacts(pid, List.of("tr-thread", "tr-session", "tr-alone", "tr-missing"));

        assertEquals(new TurnFacts("thread-x", at), facts.get("tr-thread"));
        assertEquals(new TurnFacts("sess-y", at), facts.get("tr-session"));
        assertEquals(new TurnFacts(null, at), facts.get("tr-alone"));
        assertFalse(facts.containsKey("tr-missing"));
    }

    @Test
    void aPauseIsWrittenReadAndCleared() {
        String pid = project("fr-pause");
        ClassifierRow signal = frustration(pid);
        Instant at = Instant.parse("2026-09-21T10:00:00Z");

        assertEquals(Optional.empty(), classifiers.findPause(pid, signal.id()));
        assertEquals(1, classifiers.pause(pid, signal.id(), ClassifierPause.PROVIDER_REJECTED, at));
        assertEquals(
                Optional.of(new ClassifierPause(ClassifierPause.PROVIDER_REJECTED, at)),
                classifiers.findPause(pid, signal.id()));
        assertEquals(1, classifiers.unpause(pid, signal.id()));
        assertEquals(Optional.empty(), classifiers.findPause(pid, signal.id()));
        assertEquals(0, classifiers.unpause(pid, signal.id()), "clearing twice changes nothing");
    }

    @Test
    void holdingAPageCountsAndAnyCursorMoveResetsTheCount() {
        String pid = project("fr-hold");
        ClassifierRow signal = frustration(pid);
        jobs.enqueue(pid, signal.id(), 1800);
        ClassifierJobRow job = job(pid, signal.id());
        assertEquals(0, job.pageRetries());

        jobs.holdPage(job.id());
        jobs.holdPage(job.id());
        assertEquals(2, job(pid, signal.id()).pageRetries());
        assertNull(job(pid, signal.id()).cursorId(), "a hold leaves the cursor where it was");

        jobs.markSwept(job.id(), "2026-09-21T10:00:00Z", "tr:span");
        assertEquals(0, job(pid, signal.id()).pageRetries());
    }

    // ---- fixtures

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
    }

    private ClassifierRow frustration(String pid) {
        classifierService.seedBuiltIns(pid);
        return classifiers.findByKey(pid, "frustration").orElseThrow();
    }

    private ClassifierJobRow job(String pid, String classifierId) {
        return jobs.listByProject(pid).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .findFirst()
                .orElseThrow();
    }

    /** A turn root in conversation {@code thread} (else {@code session}), with an eligible thread stubbed. */
    private SubstrateObservation turn(
            String pid, String traceId, String session, @Nullable String thread, Instant startedAt) {
        fx.trace(pid, traceId, session, thread, null, startedAt);
        SubstrateObservation obs = new SubstrateObservation(
                "span-" + traceId,
                pid,
                traceId,
                session,
                null,
                "cs-1",
                "llm",
                "chat",
                null,
                null,
                null,
                startedAt.toString());
        when(assembler.assembleStructured(obs))
                .thenReturn(Optional.of(new StructuredThread(
                        List.of(
                                text("user", "first question"),
                                text("assistant", "first answer"),
                                text("user", "second question"),
                                text("assistant", "second answer")),
                        text("user", CURRENT_PREFIX + traceId),
                        3)));
        return obs;
    }

    private static Message text(String role, String text) {
        return new Message(role, text, true, false);
    }

    /** The real detector over the real repositories, with the decision call answering from {@code scores}. */
    private JevFrustrationDetector detector(Map<String, Double> scores) {
        DecisionClient client = (projectId, lane, target, request) -> {
            String current = request.state().path("current_user_message").asText();
            String traceId = current.substring(CURRENT_PREFIX.length());
            return answer(target, request.state(), scores.getOrDefault(traceId, 0.0));
        };
        return new JevFrustrationDetector(
                new FrustrationTurnBuilder(assembler),
                client,
                providers,
                assessments,
                detections,
                classifiers,
                tx,
                new FrustrationProperties(),
                mapper,
                Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private int count(String table, String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }

    private DecisionAnswer answer(DecisionTarget target, JsonNode state, double withAssistant) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", target.modelId());
        request.set("state", state);
        request.set("questions", mapper.valueToTree(JevFrustrationQuestion.questions()));
        ObjectNode response = mapper.createObjectNode();
        response.put("model", RESPONDED);
        ObjectNode p = response.putObject("answers")
                .putObject(JevFrustrationQuestion.NAME)
                .putObject("probabilities");
        p.put(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, withAssistant);
        return new DecisionAnswer(
                ModelProvider.TYPESAFE,
                target.modelId(),
                RESPONDED,
                Map.of(
                        JevFrustrationQuestion.NAME,
                        new DecisionAnswer.Answer(
                                "choice",
                                "x",
                                null,
                                null,
                                Map.of(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, withAssistant))),
                800,
                0,
                new BigDecimal("0.000032"),
                "book-1",
                90,
                request,
                response);
    }
}

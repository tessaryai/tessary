// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierPause;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.PagedDetector.FiredTurn;
import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.catalog.PagedDetector.Status;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.TurnFacts;
import ai.tessary.classifier.frustration.StructuredThread.Message;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.config.FrustrationProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.decisions.DecisionAnswer;
import ai.tessary.llm.decisions.DecisionClient;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionRequest;
import ai.tessary.llm.decisions.DecisionTarget;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.DecisionError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One page through the Jev detector with the decision client, the conversation read and every
 * repository stubbed: what is sent, what fires, what each persisted page writes, and how a refused or
 * missing key pauses the classifier.
 */
class JevFrustrationDetectorTest {

    private static final String PROJECT = "proj-1";
    private static final String CLASSIFIER = "cls-1";
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final String RESPONDED = "typesafe/jev-1.13-20260917";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConversationThreadAssembler assembler = mock(ConversationThreadAssembler.class);
    private final DecisionProviderResolver providers = mock(DecisionProviderResolver.class);
    private final FrustrationAssessmentRepository assessments = mock(FrustrationAssessmentRepository.class);
    private final ClassifierDetectionWriteRepository detections = mock(ClassifierDetectionWriteRepository.class);
    private final ClassifierRepository classifiers = mock(ClassifierRepository.class);
    private final FrustrationProperties props = new FrustrationProperties();
    private final StubClient client = new StubClient();
    private final Map<String, TurnFacts> facts = new HashMap<>();

    private final DecisionTarget target = new DecisionTarget(
            ModelProvider.TYPESAFE, "jev-latest", URI.create("https://api.typesafe.ai/v1/systemone"), "key");

    @BeforeEach
    void wire() {
        when(classifiers.findPause(PROJECT, CLASSIFIER)).thenReturn(Optional.empty());
        when(providers.resolve(PROJECT, ModelLane.FRUSTRATION)).thenReturn(Optional.of(target));
        when(assessments.turnFacts(eq(PROJECT), any())).thenAnswer(inv -> facts);
        when(assessments.insert(any())).thenReturn(true);
        when(detections.insert(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);
    }

    private JevFrustrationDetector detector() {
        return new JevFrustrationDetector(
                new FrustrationTurnBuilder(assembler),
                client,
                providers,
                assessments,
                detections,
                classifiers,
                TransactionOperations.withoutTransaction(),
                props,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- firing

    @Test
    void aTurnFiresOnlyWhenUnhappyWithAssistantExceedsTheThreshold() {
        SubstrateObservation high = eligibleTurn("t-high", "conv-a");
        SubstrateObservation low = eligibleTurn("t-low", "conv-b");
        client.answer("t-high", 0.71, 0.04);
        client.answer("t-low", 0.39, 0.02);
        JevFrustrationDetector d = detector();

        JevFrustrationDetector.Page page = d.score(signal("{}"), List.of(high, low));
        List<FiredTurn> fired = d.complete(signal("{}"), page, PageAction.PERSIST, 5);

        assertEquals(Status.SCORED, page.status());
        assertEquals(2, page.sent());
        assertEquals(1, fired.size());
        assertEquals("t-high", fired.get(0).turn().traceId());
    }

    @Test
    void unhappyAboutSomethingElseNeverFires() {
        SubstrateObservation turn = eligibleTurn("t-other", "conv-a");
        client.answer("t-other", 0.05, 0.93);
        JevFrustrationDetector d = detector();

        List<FiredTurn> fired = d.complete(signal("{}"), d.score(signal("{}"), List.of(turn)), PageAction.PERSIST, 5);

        assertTrue(fired.isEmpty());
        verify(detections, never())
                .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theThresholdComesFromTheClassifierConfig() {
        SubstrateObservation turn = eligibleTurn("t-1", "conv-a");
        client.answer("t-1", 0.45, 0.01);
        JevFrustrationDetector d = detector();

        String strict = "{\"threshold\":0.5}";
        List<FiredTurn> fired =
                d.complete(signal(strict), d.score(signal(strict), List.of(turn)), PageAction.PERSIST, 5);

        assertTrue(fired.isEmpty(), "0.45 fires under the 0.40 default but not under 0.5");
    }

    // ---- what a persisted page writes

    @Test
    void everySentTurnIsAssessedAndEveryFiredTurnIsADetection() throws Exception {
        SubstrateObservation high = eligibleTurn("t-high", "conv-a");
        SubstrateObservation low = eligibleTurn("t-low", "conv-b");
        client.answer("t-high", 0.71, 0.04);
        client.answer("t-low", 0.12, 0.02);
        JevFrustrationDetector d = detector();

        d.complete(signal("{}"), d.score(signal("{}"), List.of(high, low)), PageAction.PERSIST, 5);

        ArgumentCaptor<Assessment> rows = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments, times(2)).insert(rows.capture());
        Assessment flagged = rows.getAllValues().stream()
                .filter(a -> a.traceId().equals("t-high"))
                .findFirst()
                .orElseThrow();
        Assessment calm = rows.getAllValues().stream()
                .filter(a -> a.traceId().equals("t-low"))
                .findFirst()
                .orElseThrow();
        String version = JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

        assertTrue(flagged.frustrated());
        assertFalse(calm.frustrated());
        assertEquals(PROJECT, flagged.projectId());
        assertEquals(CLASSIFIER, flagged.classifierId());
        assertEquals("span-t-high", flagged.spanId());
        assertEquals("conv-a", flagged.conversationId());
        assertEquals("cs-t-high", flagged.callSiteId());
        assertEquals(NOW.minusSeconds(60), flagged.turnStartedAt());
        assertEquals(version, flagged.scorerVersion());
        assertEquals("TYPESAFE", flagged.provider());
        assertEquals(RESPONDED, flagged.model(), "the row keeps the model the provider echoed");
        assertEquals(812, flagged.inputTokens());
        assertEquals(new BigDecimal("0.00003248"), flagged.costUsd());
        assertEquals(140, flagged.latencyMs());

        JsonNode request = mapper.readTree(flagged.requestJson());
        assertFalse(request.has("model"), "the model id has its own column");
        assertEquals(Objects.requireNonNull(client.sentBodies.get("t-high")).get("state"), request.get("state"));
        assertEquals(
                Objects.requireNonNull(client.sentBodies.get("t-high")).get("questions"), request.get("questions"));
        assertEquals(client.responses.get("t-high"), mapper.readTree(flagged.responseJson()));

        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(detections, times(1))
                .insert(
                        anyString(),
                        eq(BuiltInDetector.Kind.FRUSTRATION),
                        eq(PROJECT),
                        eq(CLASSIFIER),
                        eq("frustration"),
                        isNull(),
                        eq("conv-a"),
                        eq("t-high"),
                        eq("span-t-high"),
                        eq("warn"),
                        eq("high"),
                        evidence.capture());
        JsonNode e = mapper.readTree(evidence.getValue());
        assertEquals(
                List.of("model", "provider", "score", "threshold", "scorer_version", "k_turn", "call_site_id"),
                fieldNames(e));
        assertEquals(RESPONDED, e.get("model").asText());
        assertEquals("TYPESAFE", e.get("provider").asText());
        assertEquals(0.71, e.get("score").asDouble(), 1e-9);
        assertEquals(0.40, e.get("threshold").asDouble(), 1e-9);
        assertEquals(version, e.get("scorer_version").asText());
        assertEquals(3, e.get("k_turn").asInt(), "the third user message of its conversation");
        assertEquals("cs-t-high", e.get("call_site_id").asText());
    }

    @Test
    void aConversationStopsBeingSentAtItsFirstFlagOnThePage() {
        SubstrateObservation calm = eligibleTurn("t-1", "conv-a", 30);
        SubstrateObservation flagged = eligibleTurn("t-2", "conv-a", 20);
        SubstrateObservation after = eligibleTurn("t-3", "conv-a", 10);
        SubstrateObservation other = eligibleTurn("t-4", "conv-b", 5);
        client.answer("t-1", 0.1, 0.0);
        client.answer("t-2", 0.8, 0.0);
        client.answer("t-3", 0.9, 0.0);
        client.answer("t-4", 0.1, 0.0);
        JevFrustrationDetector d = detector();

        // Out of order on the page: the conversation is still scored earliest turn first.
        JevFrustrationDetector.Page page = d.score(signal("{}"), List.of(after, other, flagged, calm));
        List<FiredTurn> fired = d.complete(signal("{}"), page, PageAction.PERSIST, 5);

        assertFalse(client.requests.containsKey("t-3"), "nothing after the conversation's flag is sent");
        assertEquals(Set.of("t-1", "t-2", "t-4"), client.requests.keySet());
        assertEquals(4, page.eligible());
        assertEquals(3, page.sent());
        assertEquals(1, fired.size());
        assertEquals("t-2", fired.get(0).turn().traceId());
        verify(assessments, times(3)).insert(any());
    }

    @Test
    void aFailedCallDoesNotStopItsConversation() {
        SubstrateObservation failed = eligibleTurn("t-1", "conv-a", 20);
        SubstrateObservation next = eligibleTurn("t-2", "conv-a", 10);
        client.fail("t-1", DecisionError.PROVIDER_UNAVAILABLE);
        client.answer("t-2", 0.1, 0.0);

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(failed, next));

        assertEquals(Set.of("t-1", "t-2"), client.requests.keySet());
        assertEquals(1, page.unavailable());
    }

    @Test
    void theRequestCarriesTheBuiltStateAndTheOneQuestion() {
        SubstrateObservation turn = eligibleTurn("t-1", "conv-a");
        client.answer("t-1", 0.1, 0.1);

        detector().score(signal("{}"), List.of(turn));

        DecisionRequest sent = Objects.requireNonNull(client.requests.get("t-1"));
        assertEquals("still wrong t-1", sent.state().get("current_user_message").asText());
        assertEquals(4, sent.state().get("earlier_messages").size());
        assertEquals(Set.of(JevFrustrationQuestion.NAME), sent.questions().keySet());
        assertEquals("frustration", client.lanes.get("t-1"));
    }

    @Test
    void aHeldOrSkippedPageWritesNothing() {
        SubstrateObservation turn = eligibleTurn("t-1", "conv-a");
        client.answer("t-1", 0.9, 0.0);
        JevFrustrationDetector d = detector();
        JevFrustrationDetector.Page page = d.score(signal("{}"), List.of(turn));

        assertTrue(d.complete(signal("{}"), page, PageAction.HOLD, 5).isEmpty());
        assertTrue(d.complete(signal("{}"), page, PageAction.SKIP, 5).isEmpty());

        verify(assessments, never()).insert(any());
    }

    // ---- what is not sent

    @Test
    void anIneligibleTurnOrOneWithNoConversationIsNotSent() {
        SubstrateObservation opener = observation("t-open");
        facts.put("t-open", new TurnFacts("conv-a", NOW));
        when(assembler.assembleStructured(opener))
                .thenReturn(Optional.of(new StructuredThread(List.of(), text("user", "hello"), 1)));
        SubstrateObservation anonymous = eligibleTurn("t-anon", null);

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(opener, anonymous));

        assertEquals(0, page.sent());
        assertEquals(0, page.eligible());
        assertTrue(client.requests.isEmpty());
    }

    // ---- failures

    @Test
    void anUnavailableProviderFailsTheTurnAndIsCountedForTheHoldRule() {
        SubstrateObservation ok = eligibleTurn("t-ok", "conv-a");
        SubstrateObservation down = eligibleTurn("t-down", "conv-b");
        client.answer("t-ok", 0.9, 0.0);
        client.fail("t-down", DecisionError.PROVIDER_UNAVAILABLE);
        JevFrustrationDetector d = detector();

        JevFrustrationDetector.Page page = d.score(signal("{}"), List.of(ok, down));
        d.complete(signal("{}"), page, PageAction.PERSIST, 5);

        assertEquals(2, page.sent());
        assertEquals(1, page.unavailable());
        verify(assessments, times(1)).insert(any());
    }

    @Test
    void aMalformedAnswerFailsTheTurnWithNoRow() {
        SubstrateObservation bad = eligibleTurn("t-bad", "conv-a");
        client.fail("t-bad", DecisionError.MALFORMED_ANSWER);
        JevFrustrationDetector d = detector();

        JevFrustrationDetector.Page page = d.score(signal("{}"), List.of(bad));
        d.complete(signal("{}"), page, PageAction.PERSIST, 5);

        assertEquals(Status.SCORED, page.status());
        assertEquals(0, page.unavailable(), "a malformed answer is not an outage");
        verify(assessments, never()).insert(any());
    }

    @Test
    void aRejectedKeyPausesTheClassifierAndAbortsThePage() {
        SubstrateObservation ok = eligibleTurn("t-ok", "conv-a");
        SubstrateObservation refused = eligibleTurn("t-refused", "conv-b");
        client.answer("t-ok", 0.9, 0.0);
        client.fail("t-refused", DecisionError.PROVIDER_REJECTED);

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(ok, refused));

        assertEquals(Status.ABORTED, page.status());
        assertEquals(ClassifierPause.PROVIDER_REJECTED, page.pauseReason());
        verify(classifiers).pause(PROJECT, CLASSIFIER, ClassifierPause.PROVIDER_REJECTED, NOW);
    }

    @Test
    void noKeyPausesTheClassifierWithNoProviderAndSendsNothing() {
        when(providers.resolve(PROJECT, ModelLane.FRUSTRATION)).thenReturn(Optional.empty());

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(eligibleTurn("t-1", "conv-a")));

        assertEquals(Status.ABORTED, page.status());
        assertEquals(ClassifierPause.NO_PROVIDER, page.pauseReason());
        verify(classifiers).pause(PROJECT, CLASSIFIER, ClassifierPause.NO_PROVIDER, NOW);
        assertTrue(client.requests.isEmpty());
    }

    // ---- pause

    @Test
    void aPausedClassifierSendsNothingUntilTheRetryIntervalPasses() {
        when(classifiers.findPause(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(new ClassifierPause(ClassifierPause.PROVIDER_REJECTED, NOW.minusSeconds(60))));

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(eligibleTurn("t-1", "conv-a")));

        assertEquals(Status.PAUSED, page.status());
        assertTrue(client.requests.isEmpty());
        verify(providers, never()).resolve(any(), any());
    }

    @Test
    void aPauseOlderThanTheRetryIntervalIsCheckedAgainAndClearedWhenAKeyResolves() {
        when(classifiers.findPause(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(new ClassifierPause(
                        ClassifierPause.NO_PROVIDER, NOW.minusSeconds(props.getCredentialRetrySeconds() + 1))));
        client.answer("t-1", 0.1, 0.0);

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(eligibleTurn("t-1", "conv-a")));

        assertEquals(Status.SCORED, page.status());
        verify(classifiers).unpause(PROJECT, CLASSIFIER);
        assertEquals(1, page.sent());
    }

    @Test
    void aRecheckThatStillFindsNoKeyPassesThePageAndRestampsThePause() {
        when(classifiers.findPause(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(new ClassifierPause(
                        ClassifierPause.NO_PROVIDER, NOW.minusSeconds(props.getCredentialRetrySeconds() + 1))));
        when(providers.resolve(PROJECT, ModelLane.FRUSTRATION)).thenReturn(Optional.empty());

        JevFrustrationDetector.Page page = detector().score(signal("{}"), List.of(eligibleTurn("t-1", "conv-a")));

        assertEquals(Status.PAUSED, page.status());
        verify(classifiers).pause(PROJECT, CLASSIFIER, ClassifierPause.NO_PROVIDER, NOW);
        verify(classifiers, never()).unpause(any(), any());
    }

    @Test
    void anEmptyPageCarriesNoPauseReason() {
        assertNull(detector().score(signal("{}"), List.of()).pauseReason());
    }

    // ---- fixtures

    private static ClassifierRow signal(String configJson) {
        return new ClassifierRow(
                CLASSIFIER,
                PROJECT,
                "frustration",
                "Frustration",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                configJson,
                true,
                8,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
    }

    private static SubstrateObservation observation(String traceId) {
        return new SubstrateObservation(
                "span-" + traceId,
                PROJECT,
                traceId,
                "sess",
                null,
                "cs-" + traceId,
                "llm",
                "chat",
                null,
                null,
                null,
                "2026-09-21T11:59:00Z",
                null);
    }

    /** A turn root with a clean user, assistant, user, assistant prefix, in conversation {@code conv}. */
    private SubstrateObservation eligibleTurn(String traceId, @Nullable String conv) {
        return eligibleTurn(traceId, conv, 60);
    }

    /** As {@link #eligibleTurn(String, String)}, started {@code secondsAgo} before now. */
    private SubstrateObservation eligibleTurn(String traceId, @Nullable String conv, long secondsAgo) {
        SubstrateObservation obs = observation(traceId);
        facts.put(traceId, new TurnFacts(conv, NOW.minusSeconds(secondsAgo)));
        when(assembler.assembleStructured(obs))
                .thenReturn(Optional.of(new StructuredThread(
                        List.of(
                                text("user", "first question"),
                                text("assistant", "first answer"),
                                text("user", "second question"),
                                text("assistant", "second answer")),
                        text("user", "still wrong " + traceId),
                        3)));
        return obs;
    }

    private static Message text(String role, String text) {
        return new Message(role, text, true, false);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Answers by the trace id carried in the scored message text; records what it was asked. */
    private final class StubClient implements DecisionClient {
        final Map<String, JsonNode> responses = new ConcurrentHashMap<>();
        final Map<String, DecisionError> failures = new ConcurrentHashMap<>();
        final Map<String, DecisionRequest> requests = new ConcurrentHashMap<>();
        final Map<String, ObjectNode> sentBodies = new ConcurrentHashMap<>();
        final Map<String, String> lanes = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        void answer(String traceId, double withAssistant, double otherCause) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", RESPONDED);
            ObjectNode answer = body.putObject("answers").putObject(JevFrustrationQuestion.NAME);
            answer.put("type", "choice");
            answer.put(
                    "choice",
                    withAssistant > 0.5 ? JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT : "neutral_or_positive");
            ObjectNode p = answer.putObject("probabilities");
            p.put(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, withAssistant);
            p.put(JevFrustrationQuestion.UNHAPPY_OTHER_CAUSE, otherCause);
            p.put(JevFrustrationQuestion.NEUTRAL_OR_POSITIVE, 1 - withAssistant - otherCause);
            body.putObject("usage").put("input_tokens", 812).put("output_tokens", 0);
            responses.put(traceId, body);
        }

        void fail(String traceId, DecisionError error) {
            failures.put(traceId, error);
        }

        @Override
        public DecisionAnswer decide(String projectId, String lane, DecisionTarget t, DecisionRequest request) {
            calls.incrementAndGet();
            String current = request.state().get("current_user_message").asText();
            String traceId = current.substring("still wrong ".length());
            requests.put(traceId, request);
            lanes.put(traceId, lane);
            ObjectNode body = mapper.createObjectNode();
            body.put("model", t.modelId());
            body.set("state", request.state());
            body.set("questions", mapper.valueToTree(request.questions()));
            sentBodies.put(traceId, body);
            DecisionError error = failures.get(traceId);
            if (error != null) throw new TessaryException(error, t.provider(), "stub");
            JsonNode response = Objects.requireNonNull(responses.get(traceId));
            Map<String, Double> probabilities = new LinkedHashMap<>();
            response.path("answers")
                    .path(JevFrustrationQuestion.NAME)
                    .path("probabilities")
                    .properties()
                    .forEach(en -> probabilities.put(en.getKey(), en.getValue().doubleValue()));
            return new DecisionAnswer(
                    t.provider(),
                    t.modelId(),
                    RESPONDED,
                    Map.of(
                            JevFrustrationQuestion.NAME,
                            new DecisionAnswer.Answer("choice", "x", null, null, probabilities)),
                    812,
                    0,
                    new BigDecimal("0.00003248"),
                    "book-1",
                    140,
                    body,
                    response);
        }
    }
}

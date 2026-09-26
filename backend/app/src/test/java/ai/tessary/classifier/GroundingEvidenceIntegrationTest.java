// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SubstrateReadRepository#groundingEvidence} against real Postgres. Every other test stubs {@link
 * GroundingEvidenceReads}, and a revision of this query shipped ordering by {@code tc.seq}, a column {@code
 * tool_call} lacks, unnoticed.
 */
@SpringBootTest
class GroundingEvidenceIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    TenantService tenants;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    private static String trace() {
        return SubstrateV2Fixtures.traceId();
    }

    /** A fresh session id, for multi-turn tests that add several turns to it via {@link #spanInSession}. */
    private static String session() {
        return SubstrateV2Fixtures.sessionId();
    }

    private SpanRef span(String pid, String traceId, String kind, @Nullable String output, Instant startedAt) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .kind(kind)
                .name("chat")
                .model("gpt-x")
                .at(startedAt)
                .payload(ClassifierObservations.userInput("how long do refunds take?"), output)
                .writeRef();
    }

    /**
     * A span on a trace sharing {@code sessionId}: the {@code COALESCE(thread_id, session_id)} conversation grain,
     * one trace per turn.
     */
    private SpanRef spanInSession(
            String pid, String traceId, String sessionId, String kind, @Nullable String output, Instant startedAt) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind(kind)
                .name("chat")
                .model("gpt-x")
                .at(startedAt)
                .payload(ClassifierObservations.userInput("how long do refunds take?"), output)
                .writeRef();
    }

    /**
     * A retrieved document joined by producer keys {@code (project_id, trace_id, span_id)}, the join the read issues.
     */
    private void doc(String pid, SpanRef span, int rank, @Nullable String listRole, String content, Instant at) {
        fx.retrievedDoc(pid, span, content, rank, listRole, at);
    }

    /** The reads omit a span with no evidence, so this asserts presence. */
    private GroundingEvidenceReads.Evidence evidenceFor(String pid, SpanRef span) {
        Map<String, GroundingEvidenceReads.Evidence> got = substrate.groundingEvidence(
                pid, Set.of(new GroundingEvidenceReads.SpanRef(span.traceId(), span.spanId())));
        GroundingEvidenceReads.Evidence ev = got.get(span.spanId());
        assertNotNull(ev, "expected an evidence entry for " + span.spanId());
        return ev;
    }

    @Test
    void documentsRetrievedAfterTheAnswerAreNotEvidenceForIt() {
        // An unbounded trace join judges an early turn against passages fetched later, reporting a fabricated claim
        // as supported.
        String pid =
                TenantFixture.bootstrap(tenants, "grounding-time").project().id();
        Instant t0 = Instant.now();
        String traceId = trace();
        SpanRef answer = span(pid, traceId, "llm", "Refunds take 5-7 business days.", t0);
        SpanRef laterRetrieval = span(pid, traceId, "retrieval", null, t0.plusSeconds(30));
        doc(
                pid,
                laterRetrieval,
                0,
                "result",
                "A LATER passage the earlier answer could not have used.",
                t0.plusSeconds(30));

        GroundingEvidenceReads.Evidence got = evidenceFor(pid, answer);

        assertEquals("", String.join("\n", got.documents()), "evidence must be bounded to what preceded the answer");
        assertTrue(got.conversationDidExternalWork(), "the trace still reached outside — this is BLIND");
    }

    @Test
    void rerankedDuplicatesCollapseAndDiscardedCandidatesAreDropped() {
        String pid =
                TenantFixture.bootstrap(tenants, "grounding-rerank").project().id();
        Instant at = Instant.now();
        String traceId = trace();
        SpanRef retrieval = span(pid, traceId, "retrieval", null, at);
        SpanRef reranker = span(pid, traceId, "reranker", null, at);
        SpanRef answer = span(pid, traceId, "llm", "Refunds take 5-7 days.", at);

        doc(pid, retrieval, 0, "result", "Refunds are issued within 5-7 business days.", at);
        // the reranker re-emits the same passage it kept, and records what it threw away
        doc(pid, reranker, 0, "result", "Refunds are issued within 5-7 business days.", at);
        doc(pid, reranker, 1, "candidate", "Gift cards are non-refundable under any circumstances.", at);

        String text = String.join("\n", evidenceFor(pid, answer).documents());

        assertEquals(
                1,
                text.split("Refunds are issued", -1).length - 1,
                "a rerank stage re-emits its survivors; the same passage must appear once");
        assertFalse(
                text.contains("Gift cards"), "a passage the pipeline discarded must not be able to ground an answer");
    }

    @Test
    void evidenceIsCappedByRowCount() {
        String pid = TenantFixture.bootstrap(tenants, "grounding-cap").project().id();
        Instant at = Instant.now();
        String traceId = trace();
        SpanRef retrieval = span(pid, traceId, "retrieval", null, at);
        SpanRef answer = span(pid, traceId, "llm", "Refunds take 5-7 days.", at);
        for (int i = 0; i < 20; i++) {
            doc(pid, retrieval, i, "result", "passage number " + i + " about refunds", at);
        }

        String text = String.join("\n", evidenceFor(pid, answer).documents());

        // Assert the boundary: with LIMIT inside the DISTINCT ON subquery (the bug), Postgres returns 0,10..14, which
        // passes presence checks. Only ranks 5 and 6 tell sorted-then-capped apart.
        assertEquals(6, text.lines().count(), "the premise is capped by row count, best-rank-first, not just per row");
        assertTrue(text.contains("passage number 0 about refunds"), "the cap keeps the best-ranked passages");
        assertTrue(
                text.contains("passage number 5 about refunds"),
                "ranks 0-5 are the six best — the sort happens BEFORE the cap");
        assertFalse(text.contains("passage number 6 about refunds"), "rank 6 is the first one over the cap");
        assertFalse(
                text.contains("passage number 19 about refunds"), "and the worst-ranked are nowhere near the premise");
    }

    @Test
    void anEmbeddingSpanCountsAsReachingOutside() {
        // Every tool-like kind counts as reaching outside; omitting one makes its traces read GROUNDLESS instead of
        // BLIND.
        String pid = TenantFixture.bootstrap(tenants, "grounding-embedding")
                .project()
                .id();
        Instant at = Instant.now();
        String traceId = trace();
        span(pid, traceId, "embedding", null, at);
        SpanRef answer = span(pid, traceId, "llm", "Refunds take 5-7 days.", at);

        assertTrue(evidenceFor(pid, answer).conversationDidExternalWork());
    }

    @Test
    void aTraceThatReachedNowhereIsNotBlind() {
        String pid = TenantFixture.bootstrap(tenants, "grounding-groundless")
                .project()
                .id();
        Instant at = Instant.now();
        String traceId = trace();
        SpanRef answer = span(pid, traceId, "llm", "Refunds take 5-7 days.", at);

        GroundingEvidenceReads.Evidence got = substrate
                .groundingEvidence(pid, Set.of(new GroundingEvidenceReads.SpanRef(traceId, answer.spanId())))
                .get(answer.spanId());

        // Never "reached outside": the prompt is the whole world, so the detector falls back to it.
        assertFalse(got != null && got.conversationDidExternalWork());
    }

    @Test
    void aToolSpanMakesTheTraceBlindWithoutContributingEvidence() {
        String pid =
                TenantFixture.bootstrap(tenants, "grounding-tool").project().id();
        Instant at = Instant.now();
        String traceId = trace();
        span(pid, traceId, "tool", "{\"status\":\"shipped\"}", at);
        SpanRef answer = span(pid, traceId, "llm", "Your order shipped on 3 March.", at);

        GroundingEvidenceReads.Evidence got = evidenceFor(pid, answer);

        assertEquals("", String.join("\n", got.documents()), "tool results are not an evidence carrier");
        assertTrue(got.conversationDidExternalWork(), "but the trace did reach outside, which is what abstains it");
    }

    @Test
    void aFollowUpTurnSeesTheEarlierTurnsRetrievalInTheSameConversation() {
        // A follow-up answered from an earlier turn's retrieval once had no evidence: 88.4% false fire on a 500-case
        // eval, vs 8.6% same-trace.
        String pid = TenantFixture.bootstrap(tenants, "grounding-conversation")
                .project()
                .id();
        Instant t0 = Instant.now();
        String sessionId = session();

        String turn0 = trace();
        SpanRef retrieval = spanInSession(pid, turn0, sessionId, "retrieval", null, t0);
        doc(pid, retrieval, 0, "result", "Refunds are issued within 5-7 business days.", t0);
        spanInSession(pid, turn0, sessionId, "llm", "Refunds take 5-7 business days.", t0);

        // turn 1: its own trace, no retrieval
        String turn1 = trace();
        SpanRef followUp =
                spanInSession(pid, turn1, sessionId, "llm", "Just to confirm, 5-7 business days.", t0.plusSeconds(30));

        GroundingEvidenceReads.Evidence got = evidenceFor(pid, followUp);

        assertTrue(
                String.join("\n", got.documents()).contains("5-7 business days"),
                "turn 1 has no retrieval of its own — conversation scope is the only way it sees turn 0's");
        assertTrue(got.conversationDidExternalWork());
    }

    @Test
    void aTurnInADifferentConversationDoesNotLeakEvidence() {
        // Two conversations never share evidence, even in one project close in time.
        String pid =
                TenantFixture.bootstrap(tenants, "grounding-no-leak").project().id();
        Instant at = Instant.now();

        String sessionA = session();
        String traceA = trace();
        SpanRef retrievalA = spanInSession(pid, traceA, sessionA, "retrieval", null, at);
        doc(pid, retrievalA, 0, "result", "Refunds are issued within 5-7 business days.", at);
        spanInSession(pid, traceA, sessionA, "llm", "Refunds take 5-7 business days.", at);

        String sessionB = session();
        String traceB = trace();
        SpanRef answerB = spanInSession(pid, traceB, sessionB, "llm", "Refunds take 5-7 business days.", at);

        GroundingEvidenceReads.Evidence got = substrate
                .groundingEvidence(pid, Set.of(new GroundingEvidenceReads.SpanRef(answerB.traceId(), answerB.spanId())))
                .get(answerB.spanId());

        // Never conversation A's document.
        assertTrue(
                got == null || (String.join("\n", got.documents()).isEmpty() && !got.conversationDidExternalWork()),
                "conversation B must not see conversation A's retrieval");
    }

    @Test
    void theNearestPriorRetrievalWinsNotAConversationWideBlend() {
        // A topic shift hands a follow-up the nearest prior retrieval, not a blend of all of them.
        String pid =
                TenantFixture.bootstrap(tenants, "grounding-nearest").project().id();
        Instant t0 = Instant.now();
        String sessionId = session();

        String turn0 = trace();
        SpanRef retrieval0 = spanInSession(pid, turn0, sessionId, "retrieval", null, t0);
        doc(pid, retrieval0, 0, "result", "SET-A: refunds take 5-7 business days.", t0);
        spanInSession(pid, turn0, sessionId, "llm", "Refunds take 5-7 business days.", t0);

        String turn1 = trace();
        SpanRef retrieval1 = spanInSession(pid, turn1, sessionId, "retrieval", null, t0.plusSeconds(30));
        doc(pid, retrieval1, 0, "result", "SET-B: returns are free within 30 days.", t0.plusSeconds(30));
        spanInSession(pid, turn1, sessionId, "llm", "Returns are free within 30 days.", t0.plusSeconds(30));

        String turn2 = trace();
        SpanRef followUp =
                spanInSession(pid, turn2, sessionId, "llm", "Returns are free for 30 days.", t0.plusSeconds(60));

        String text = String.join("\n", evidenceFor(pid, followUp).documents());

        assertTrue(text.contains("SET-B"), "the nearest prior retrieval (turn 1) must be the evidence");
        assertFalse(text.contains("SET-A"), "an OLDER retrieval (turn 0) must not blend into the premise");
    }
}

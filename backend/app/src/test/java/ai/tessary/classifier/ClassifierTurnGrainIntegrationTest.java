// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.tessary.classifier.ClassifierDtos.ClassifierEventView;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierConversations;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Frustration is {@link ClassifierModelModule.Grain#TURN}: its subject is what the USER said, and the
 * user says it once per turn. A single user-facing turn lands in the substrate as many spans — the
 * agent span, its llm child carrying the same delta, inner planner/summarizer calls, tool spans, plus
 * any sub-agent trace — and scoring each of them would draw the head's calibrated per-item
 * false-positive rate several times over ONE user message, and emit several verdicts for it.
 *
 * <p>Pins the structural rule (root trace + root span + dialogue kind) against the real Postgres, both
 * ways round: the fan-out under a turn collapses to exactly one detection ON THE ROOT, and a product
 * with no agent wrapper — whose root IS a bare {@code llm} span — is still scored, which a naive
 * {@code kind='agent'} filter would have silenced.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class ClassifierTurnGrainIntegrationTest {

    private static final String FRUSTRATED = "this is frustrating, you're not listening";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ClassifierWorker worker;

    @Autowired
    ClassifierService service;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    /**
     * Frustration is one of the four paid classifiers and OFF by default in an open build
     * (#887/#888) — every test in this file is about frustration's own behaviour, so every one grants
     * it explicitly, before its project is created, the same way {@code ClassifierDefinitionIntegrationTest}
     * grants behaviour drift and SOP conformance.
     */
    private String bootstrapGranted(String testName) {
        return TenantFixture.bootstrap(tenants, testName, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
    }

    @Test
    void oneTurnFiresOnceOnItsRootSpanDespiteTheSpanFanOut() {
        String pid = bootstrapGranted("turn-grain");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration skips a conversation opener; seed the preamble so the turn under test is scoreable.
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, now.toString());

        String rootTraceId = SubstrateV2Fixtures.traceId();
        // The turn ROOT — the only user-facing unit here. Every other span below carries the same
        // frustrated user text, so any leak into the candidate set shows up as an extra detection.
        SpanRef root = seedSpan(pid, rootTraceId, sessionId, null, "agent", "agent", now);
        // The agent/llm TWIN: the same turn delta re-emitted as the agent's llm child.
        SpanRef twin = seedSpan(pid, rootTraceId, sessionId, root.spanId(), "llm", "chat", now);
        // An INNER call under the twin (a planner/summarizer step), two levels down.
        seedSpan(pid, rootTraceId, sessionId, twin.spanId(), "llm", "chat", now);

        // A SUB-AGENT trace nested under the turn's trace: its own root span, parentless within its
        // trace, and kind=agent — indistinguishable from the turn root by kind alone, and excluded only
        // by parent_trace_id.
        String subTraceId = SubstrateV2Fixtures.traceId();
        seedSubAgentTrace(pid, subTraceId, rootTraceId, sessionId, now);
        seedSpan(pid, subTraceId, sessionId, null, "agent", "sub", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size(), "one user-facing turn produces exactly one frustration detection");
        // The subject is the TURN, and the turn is the trace. Frustration is a claim about a user's
        // exchange, not about one span inside it, and its detection table's unique key says so — which
        // is also what closed the cross-batch duplicate the span-keyed verdict could not.
        assertEquals("trace", events.get(0).subjectKind());
        assertEquals(
                rootTraceId,
                events.get(0).subjectId(),
                "the detection is anchored on the turn, not the twin, an inner call, or a sub-agent");
        assertEquals(rootTraceId, events.get(0).traceId());
    }

    @Test
    void aSecondFrustratedTurnInAnAlreadyFlaggedConversationIsNotScoredAgain() {
        // A conversation is ONE event, not one per turn. interview-coach carried 8,012 frustration
        // detections over 987 conversations (2026-08-20) — 8.12 rows per conversation, each a separate
        // encoder call, all saying the same thing about the same conversation.
        String pid = bootstrapGranted("turn-grain-convo");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, now.toString());

        String firstTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, firstTurn, sessionId, null, "agent", "agent", now);
        List<ClassifierEventView> afterFirst = sweepUntilDetected(pid);
        assertEquals(1, afterFirst.size(), "the first frustrated turn flags the conversation");

        // A SECOND frustrated turn, same conversation, later. Same text, so it would score identically
        // — the only reason not to flag it is that its conversation is already flagged.
        String secondTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, secondTurn, sessionId, null, "agent", "agent", now.plusSeconds(30));
        sweepOnce(pid);

        List<ClassifierEventView> afterSecond = service.eventsForClassifier(
                pid, signals.findByKey(pid, "frustration").orElseThrow().id(), 100);
        assertEquals(
                1,
                afterSecond.size(),
                "the conversation is already flagged at high, so the second turn is not scored again");
        assertEquals(
                firstTurn,
                afterSecond.get(0).subjectId(),
                "the surviving detection is the FIRST turn that earned it, not the last one seen");
    }

    @Test
    void aTurnInAnUnflaggedConversationIsStillScored() {
        // The suppression is per CONVERSATION, not global: a different conversation is a different
        // event and must still be able to flag. This is the assertion that fails if the filter ever
        // widens from "this session is flagged" to "anything is flagged".
        String pid = bootstrapGranted("turn-grain-convo2");
        Instant now = Instant.now();

        String flaggedSession = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPriorTurn(fx, pid, flaggedSession, now.toString());
        seedSpan(pid, SubstrateV2Fixtures.traceId(), flaggedSession, null, "agent", "agent", now);
        assertEquals(1, sweepUntilDetected(pid).size());

        String otherSession = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPriorTurn(
                fx, pid, otherSession, now.plusSeconds(60).toString());
        String otherTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, otherTurn, otherSession, null, "agent", "agent", now.plusSeconds(90));
        sweepOnce(pid);

        List<ClassifierEventView> events = service.eventsForClassifier(
                pid, signals.findByKey(pid, "frustration").orElseThrow().id(), 100);
        assertEquals(2, events.size(), "a DIFFERENT conversation still flags — suppression is per conversation");
        assertTrue(
                events.stream().anyMatch(e -> otherTurn.equals(e.subjectId())),
                "the unflagged conversation's turn produced its own detection");
    }

    @Test
    void aBareLlmRootWithNoAgentWrapperIsStillScored() {
        String pid = bootstrapGranted("turn-grain-bare");
        Instant now = Instant.now();

        // The no-agent-wrapper shape: one llm call per turn, emitted as the trace's root span. Its root
        // IS the user-facing turn, which is why the filter tests structure (root trace + root span) and
        // only requires kind to be dialogue-bearing, rather than requiring kind='agent'.
        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, now.toString());
        SpanRef only = seedSpan(pid, SubstrateV2Fixtures.traceId(), sessionId, null, "llm", "chat", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size(), "a bare llm root is a user-facing turn and must still be scored");
        assertEquals(only.traceId(), events.get(0).subjectId());
    }

    /** A frustrated user turn in the shape ingest really stores (role-tagged gen_ai envelope). */
    private SpanRef seedSpan(
            String pid,
            String traceId,
            String sessionId,
            @Nullable String parentSpanId,
            String kind,
            String name,
            Instant at) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .parentSpanId(parentSpanId)
                .kind(kind)
                .name(name)
                .model("llm".equals(kind) ? "gpt-x" : null)
                .at(at)
                .payload(
                        ClassifierObservations.userInput(FRUSTRATED),
                        ClassifierObservations.assistantOutput("I've already told you that isn't supported."))
                .writeRef();
    }

    /**
     * A trace nested under {@code parentTraceId} — the sub-agent shape. {@code TraceV2Row.of} cannot
     * express it (an arrival never claims a parent trace; §9 reserves it for a sub-agent that OUTLIVED
     * the turn), so the row is built whole.
     */
    private void seedSubAgentTrace(String pid, String id, String parentTraceId, String sessionId, Instant at) {
        fx.session(pid, sessionId, at);
        traces.getOrCreate(new TraceV2Row(
                pid,
                id,
                sessionId,
                parentTraceId,
                null,
                null,
                null,
                null,
                null,
                at.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                at.toString(),
                false));
    }

    /** Seed the built-ins, then tick until the frustration sweep has caught up. */
    /** A few plain ticks — enough for a newly seeded turn to be swept, without asserting it fired. */
    private void sweepOnce(String pid) {
        for (int tick = 0; tick < 5; tick++) {
            service.seedBuiltIns(pid);
            worker.tick();
            sleep(150);
        }
    }

    private List<ClassifierEventView> sweepUntilDetected(String pid) {
        ClassifierRow frustration = null;
        for (int tick = 0; tick < 50; tick++) {
            service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
            worker.tick();
            if (frustration == null) {
                frustration = signals.findByKey(pid, "frustration").orElse(null);
            }
            if (frustration != null
                    && !service.eventsForClassifier(pid, frustration.id(), 100).isEmpty()) {
                // One more tick past the first detection so a LEAKED extra candidate (which would be
                // swept right behind it) has a chance to land and fail the count assertion below.
                worker.tick();
                sleep(200);
                return service.eventsForClassifier(pid, frustration.id(), 100);
            }
            sleep(100);
        }
        return fail("frustration never detected — the sweep did not reach the turn root");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

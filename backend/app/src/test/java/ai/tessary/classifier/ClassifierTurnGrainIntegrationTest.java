// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.ClassifierSweeps;
import ai.tessary.testsupport.StubDecisionClientConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Frustration is {@link ClassifierModelModule.Grain#TURN}: one user message per turn and picked call site, though a
 * turn lands as many spans. Pins the rule (top-level trace, dialogue kind, the first span of a picked call site)
 * against real Postgres: a turn that calls a router, the reply and a memory pass is scored once, on the reply; a
 * sub-agent trace is not a turn; and a bare {@code llm} root with no agent wrapper is still scored, which a {@code
 * kind='agent'} filter would silence.
 */
@SpringBootTest
@Import(StubDecisionClientConfig.class)
class ClassifierTurnGrainIntegrationTest {

    private static final String FRUSTRATED = "this is frustrating, you're not listening";
    private static final String REPLY = ClassifierConversations.CALL_SITE;

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

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    /** Frustration seeds disabled (it spends provider credit), so each test grants it and turns it on. */
    private String bootstrapGranted(String testName) {
        return bootstrapGranted(testName, List.of(REPLY));
    }

    /** As {@link #bootstrapGranted(String)}, limited to {@code callSiteIds}, or on every call site with null. */
    private String bootstrapGranted(String testName, @Nullable List<String> callSiteIds) {
        String pid = TenantFixture.bootstrap(
                        tenants, testName, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        service.seedBuiltIns(pid);
        String id =
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow().id();
        // Straight to the row: the service refuses a call site no trace has reached yet.
        if (callSiteIds != null) signals.setCallSiteIds(pid, id, callSiteIds);
        service.setEnabled(pid, id, true);
        return pid;
    }

    /**
     * The shape of a chat app that calls a router, then the reply, then a memory pass, each its own call site, under
     * one wrapper span. Every span carries the frustrated text, so any span scored besides the reply's first shows as
     * an extra detection. Read by root span, the turn is the wrapper's; read by trace, the router is first.
     */
    @Test
    void aTurnIsScoredOnceOnTheFirstSpanOfThePickedCallSite() {
        String pid = bootstrapGranted("turn-grain");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration sends a turn only after two earlier exchanges.
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());

        String turnTraceId = SubstrateV2Fixtures.traceId();
        SpanRef wrapper = seedSpan(pid, turnTraceId, sessionId, null, null, "agent", "agent", now);
        seedSpan(pid, turnTraceId, sessionId, wrapper.spanId(), "cs-router", "llm", "chat", now.plusMillis(1));
        SpanRef reply =
                seedSpan(pid, turnTraceId, sessionId, wrapper.spanId(), REPLY, "llm", "chat", now.plusMillis(2));
        seedSpan(pid, turnTraceId, sessionId, wrapper.spanId(), REPLY, "llm", "chat", now.plusMillis(3));
        seedSpan(pid, turnTraceId, sessionId, wrapper.spanId(), "cs-memory", "llm", "chat", now.plusMillis(4));

        // A sub-agent trace on the reply call site: excluded only by parent_trace_id.
        String subTraceId = SubstrateV2Fixtures.traceId();
        seedSubAgentTrace(pid, subTraceId, turnTraceId, sessionId, now);
        seedSpan(pid, subTraceId, sessionId, null, REPLY, "agent", "sub", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size(), "one user-facing turn produces exactly one frustration detection");
        // The subject is the turn, which is the trace.
        assertEquals("trace", events.get(0).subjectKind());
        assertEquals(turnTraceId, events.get(0).subjectId(), "the detection is anchored on the turn, not a sub-agent");
        assertEquals(
                reply.spanId(),
                flaggedSpan(pid, turnTraceId),
                "the reply's first call, not the router, the memory pass or the reply's later call");
    }

    /** With no list, Frustration runs on every call site, like every other classifier, so nothing has to be picked. */
    @Test
    void withNoListATurnIsScored() {
        String pid = bootstrapGranted("turn-grain-every", null);
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());
        SpanRef turn = seedSpan(pid, SubstrateV2Fixtures.traceId(), sessionId, null, REPLY, "llm", "chat", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size());
        assertEquals(turn.traceId(), events.get(0).subjectId());
    }

    @Test
    void aSecondFrustratedTurnInAnAlreadyFlaggedConversationIsNotScoredAgain() {
        // A conversation is one event, not one per turn.
        String pid = bootstrapGranted("turn-grain-convo");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());

        String firstTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, firstTurn, sessionId, null, REPLY, "agent", "agent", now);
        List<ClassifierEventView> afterFirst = sweepUntilDetected(pid);
        assertEquals(1, afterFirst.size(), "the first frustrated turn flags the conversation");

        // Same text, so it would score identically; only the flagged conversation keeps it quiet.
        String secondTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, secondTurn, sessionId, null, REPLY, "agent", "agent", now.plusSeconds(30));
        sweepOnce(pid);

        List<ClassifierEventView> afterSecond = service.eventsForClassifier(
                pid,
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow().id(),
                null,
                100);
        assertEquals(
                1, afterSecond.size(), "the conversation is already flagged, so the second turn is not scored again");
        assertEquals(
                firstTurn,
                afterSecond.get(0).subjectId(),
                "the surviving detection is the FIRST turn that earned it, not the last one seen");
    }

    @Test
    void aBareLlmRootWithNoAgentWrapperIsStillScored() {
        String pid = bootstrapGranted("turn-grain-bare");
        Instant now = Instant.now();

        // No agent wrapper: the llm call is the trace's root span and the user-facing turn.
        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());
        SpanRef only = seedSpan(pid, SubstrateV2Fixtures.traceId(), sessionId, null, REPLY, "llm", "chat", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size(), "a bare llm root is a user-facing turn and must still be scored");
        assertEquals(only.traceId(), events.get(0).subjectId());
    }

    /** A frustrated user turn in the stored role-tagged gen_ai envelope. */
    private SpanRef seedSpan(
            String pid,
            String traceId,
            String sessionId,
            @Nullable String parentSpanId,
            @Nullable String callSiteId,
            String kind,
            String name,
            Instant at) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .parentSpanId(parentSpanId)
                .callSiteId(callSiteId)
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
     * A trace nested under {@code parentTraceId}, the sub-agent shape, built whole since {@code TraceV2Row.of} cannot
     * express it.
     */
    private void seedSubAgentTrace(String pid, String id, String parentTraceId, String sessionId, Instant at) {
        fx.session(pid, sessionId, at);
        traces.getOrCreateAll(List.of(new TraceV2Row(
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
                false)));
    }

    /** The span the classifier flagged in {@code traceId}. */
    private String flaggedSpan(String pid, String traceId) {
        return jdbc.sql(
                        "SELECT subject_span_id FROM frustration_detection WHERE project_id = :pid AND subject_trace_id = :trace")
                .param("pid", pid)
                .param("trace", traceId)
                .query(String.class)
                .single();
    }

    /** Ticks until the sweep has read past the newest span, without asserting it fired. */
    private void sweepOnce(String pid) {
        ClassifierRow frustration =
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow();
        for (int tick = 0; tick < 5; tick++) {
            service.seedBuiltIns(pid);
            worker.tick();
            ClassifierSweeps.awaitDone(jdbc, pid, frustration.id());
            if (ClassifierSweeps.sweptToNewestSpan(jdbc, pid, frustration.id())) return;
        }
        fail("the sweep never read past the newest span");
    }

    private List<ClassifierEventView> sweepUntilDetected(String pid) {
        ClassifierRow frustration = null;
        for (int tick = 0; tick < 50; tick++) {
            service.seedBuiltIns(pid); // idempotent
            worker.tick();
            if (frustration == null) {
                frustration = ClassifierRows.byKey(signals, pid, "frustration").orElse(null);
            }
            if (frustration == null) continue;
            ClassifierSweeps.awaitDone(jdbc, pid, frustration.id());
            if (!service.eventsForClassifier(pid, frustration.id(), null, 100).isEmpty()) {
                // One more tick, so a leaked extra candidate lands and fails the count.
                sweepOnce(pid);
                return service.eventsForClassifier(pid, frustration.id(), null, 100);
            }
        }
        return fail("frustration never detected — the sweep did not reach the turn root");
    }
}

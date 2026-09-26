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

/**
 * Frustration is {@link ClassifierModelModule.Grain#TURN}: one user message per turn, though a turn lands as many
 * spans. Pins the structural rule (root trace, root span, dialogue kind) against real Postgres both ways: the fan-out
 * collapses to one detection, and a bare {@code llm} root with no agent wrapper is still scored, which a {@code
 * kind='agent'} filter would silence.
 */
@SpringBootTest
@Import(StubDecisionClientConfig.class)
class ClassifierTurnGrainIntegrationTest {

    private static final String FRUSTRATED = "this is frustrating, you're not listening";

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

    /** Frustration seeds disabled (it spends provider credit), so each test grants it and turns it on. */
    private String bootstrapGranted(String testName) {
        String pid = TenantFixture.bootstrap(
                        tenants, testName, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        service.seedBuiltIns(pid);
        service.setEnabled(
                pid,
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow().id(),
                true);
        return pid;
    }

    @Test
    void oneTurnFiresOnceOnItsRootSpanDespiteTheSpanFanOut() {
        String pid = bootstrapGranted("turn-grain");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration sends a turn only after two earlier exchanges.
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());

        String rootTraceId = SubstrateV2Fixtures.traceId();
        // The turn root. Every other span carries the same frustrated text, so a leak shows as an extra detection.
        SpanRef root = seedSpan(pid, rootTraceId, sessionId, null, "agent", "agent", now);
        SpanRef twin = seedSpan(pid, rootTraceId, sessionId, root.spanId(), "llm", "chat", now);
        seedSpan(pid, rootTraceId, sessionId, twin.spanId(), "llm", "chat", now);

        // A sub-agent trace: its root is parentless and kind=agent, excluded only by parent_trace_id.
        String subTraceId = SubstrateV2Fixtures.traceId();
        seedSubAgentTrace(pid, subTraceId, rootTraceId, sessionId, now);
        seedSpan(pid, subTraceId, sessionId, null, "agent", "sub", now);

        List<ClassifierEventView> events = sweepUntilDetected(pid);

        assertEquals(1, events.size(), "one user-facing turn produces exactly one frustration detection");
        // The subject is the turn, which is the trace.
        assertEquals("trace", events.get(0).subjectKind());
        assertEquals(
                rootTraceId,
                events.get(0).subjectId(),
                "the detection is anchored on the turn, not the twin, an inner call, or a sub-agent");
        assertEquals(rootTraceId, events.get(0).traceId());
    }

    @Test
    void aSecondFrustratedTurnInAnAlreadyFlaggedConversationIsNotScoredAgain() {
        // A conversation is one event, not one per turn.
        String pid = bootstrapGranted("turn-grain-convo");
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());

        String firstTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, firstTurn, sessionId, null, "agent", "agent", now);
        List<ClassifierEventView> afterFirst = sweepUntilDetected(pid);
        assertEquals(1, afterFirst.size(), "the first frustrated turn flags the conversation");

        // Same text, so it would score identically; only the flagged conversation keeps it quiet.
        String secondTurn = SubstrateV2Fixtures.traceId();
        seedSpan(pid, secondTurn, sessionId, null, "agent", "agent", now.plusSeconds(30));
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
        SpanRef only = seedSpan(pid, SubstrateV2Fixtures.traceId(), sessionId, null, "llm", "chat", now);

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

    /** A few plain ticks: enough for a newly seeded turn to be swept, without asserting it fired. */
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
            service.seedBuiltIns(pid); // idempotent
            worker.tick();
            if (frustration == null) {
                frustration = ClassifierRows.byKey(signals, pid, "frustration").orElse(null);
            }
            if (frustration != null
                    && !service.eventsForClassifier(pid, frustration.id(), null, 100)
                            .isEmpty()) {
                // One more tick, so a leaked extra candidate lands and fails the count.
                worker.tick();
                sleep(200);
                return service.eventsForClassifier(pid, frustration.id(), null, 100);
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierDtos.ClassifierEventView;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The session a detection names, which the Classifiers rail links to. A Frustration row written before {@code 0034}
 * holds the trace's thread id in {@code subject_session_id}, so a threaded turn's detection must read its session off
 * the flagged trace, or the rail links to a session page that does not exist.
 */
@SpringBootTest
class ClassifierDetectionSessionIntegrationTest {

    private static final String THREAD = "thread-1";

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ClassifierService service;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Test
    void aThreadedFrustrationDetection_namesTheFlaggedTraceSession_notTheConversationKey() {
        String pid = bootstrap("detection-session-threaded");
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = insertTurn(pid, sessionId, THREAD);

        List<ClassifierEventView> events = detectAndRead(pid, traceId);

        assertEquals(1, events.size());
        assertEquals(
                sessionId,
                events.get(0).sessionId(),
                "the rail links to sessions/<session_id>, so it must be session.id, not the thread key");
    }

    @Test
    void aThreadedFrustrationDetectionOnATraceWithNoSession_namesNoSession() {
        String pid = bootstrap("detection-session-anonymous");
        String traceId = insertTurn(pid, null, THREAD);

        List<ClassifierEventView> events = detectAndRead(pid, traceId);

        assertEquals(1, events.size());
        assertNull(events.get(0).sessionId(), "no session, so the rail falls back to the trace link");
    }

    private String bootstrap(String name) {
        String pid = TenantFixture.bootstrap(tenants, name, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        service.seedBuiltIns(pid);
        return pid;
    }

    private String insertTurn(String pid, @Nullable String sessionId, String threadId) {
        return fx.spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(sessionId)
                .threadId(threadId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(Instant.now())
                .payload(ClassifierObservations.userInput("this is frustrating"), "ok")
                .writeRef()
                .traceId();
    }

    /** Writes the detection the way Frustration did before {@code 0034}, keyed on the thread, and reads the rail's list. */
    private List<ClassifierEventView> detectAndRead(String pid, String traceId) {
        ClassifierRow frustration =
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow();
        detections.insert(
                Ids.ulid(),
                frustration.detector(),
                pid,
                frustration.id(),
                frustration.classifierKey(),
                null,
                THREAD,
                traceId,
                null,
                Detection.Severity.WARN,
                Detection.Confidence.HIGH,
                null);
        return service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.DISCOVERY, 100);
    }
}

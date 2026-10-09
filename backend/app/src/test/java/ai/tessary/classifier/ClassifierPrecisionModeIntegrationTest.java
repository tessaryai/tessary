// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Discovery and tracking modes against real Postgres. Detections carry a HIGH or LOW band and the mode is a read-time
 * filter: discovery shows both, tracking only HIGH, the metrics endpoint reports each mode's recall and precision,
 * and toggling loses no history. Detections are written straight into Frustration's table, since the read side is the
 * same for every classifier.
 */
@SpringBootTest
class ClassifierPrecisionModeIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

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

    @Test
    void oneSignalTwoModes_differingPrecisionRecallSurfaced() {
        String pid = TenantFixture.bootstrap(
                        tenants, "signal-modes", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        Instant base = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        String weakTurn = insertTurn(pid, sessionId, "the output was a bit frustrating to read", base);
        String strongTurn =
                insertTurn(pid, sessionId, "This is frustrating, you're not listening to me", base.plusSeconds(1));

        service.seedBuiltIns(pid); // addressable up front
        ClassifierRow frustration =
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow();
        detect(pid, frustration, sessionId, weakTurn, Detection.Confidence.LOW);
        detect(pid, frustration, sessionId, strongTurn, Detection.Confidence.HIGH);

        // Frustration seeds at TRACKING (BuiltInClassifierCatalog); both bands persist regardless.
        assertEquals(ClassifierRow.Mode.TRACKING, frustration.mode(), "frustration seeds at the tracking bar");
        List<ClassifierDtos.ClassifierEventView> discovery =
                service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.DISCOVERY, 100);
        assertEquals(2, discovery.size(), "discovery surfaces both the strong and weak hits (recall)");

        // Tracking: only the HIGH hit.
        List<ClassifierDtos.ClassifierEventView> tracking =
                service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.TRACKING, 100);
        assertEquals(1, tracking.size(), "tracking surfaces only the HIGH-confidence hit (precision)");
        assertEquals(strongTurn, tracking.get(0).subjectId(), "the strong-phrase turn is the precise hit");
        assertEquals(Detection.Confidence.HIGH, tracking.get(0).confidence());
        assertTrue(
                discovery.stream()
                        .anyMatch(
                                e -> weakTurn.equals(e.subjectId()) && Detection.Confidence.LOW.equals(e.confidence())),
                "the weak-phrase turn is the LOW-confidence hit discovery adds over tracking");

        ClassifierService.ClassifierMetrics m = service.metrics(pid, frustration.id());
        assertEquals(2, m.discoveryFired(), "discovery fired count");
        assertEquals(1, m.trackingFired(), "tracking fired count");
        assertEquals(1, m.lowConfidence(), "the recall delta discovery buys over tracking");

        // Flipping the mode loses no history. Toward DISCOVERY, since the signal seeds at TRACKING.
        ClassifierRow widened = service.setMode(pid, frustration.id(), ClassifierRow.Mode.DISCOVERY);
        assertEquals(ClassifierRow.Mode.DISCOVERY, widened.mode());
        assertEquals(
                frustration.version(),
                widened.version(),
                "setting the mode does not bump the definition version (it is an operating point, not an edit)");
        assertEquals(
                2,
                service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.DISCOVERY, 100)
                        .size(),
                "history is intact after the mode flip — discovery still sees both bands");
    }

    /** One turn: its own trace with a root llm span. */
    private String insertTurn(String pid, String sessionId, String input, Instant at) {
        return fx.spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(at)
                .payload(ClassifierObservations.userInput(input), "ok")
                .writeRef()
                .traceId();
    }

    private void detect(String pid, ClassifierRow signal, String sessionId, String traceId, String confidence) {
        detections.insert(
                Ids.ulid(),
                signal.detector(),
                pid,
                signal.id(),
                signal.classifierKey(),
                null,
                sessionId,
                traceId,
                null,
                Detection.Severity.WARN,
                confidence,
                null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.Detection;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Discovery and tracking modes against real Postgres. Detections carry a HIGH or LOW band and the mode is a read-time
 * filter: discovery shows both, tracking only HIGH, the metrics endpoint reports each mode's recall and precision,
 * and toggling loses no history. Detections are written straight into Frustration's table, since the read side is the
 * same for every classifier.
 */
@SpringBootTest
record ClassifierPrecisionModeIntegrationTest(
        SubstrateV2Fixtures fx,
        ClassifierService service,
        ClassifierRepository signals,
        ClassifierDetectionWriteRepository detections,
        TenantService tenants,
        CapabilityFixture capabilities) {

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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierConversations;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance for the discovery-vs-tracking precision modes. The same frustration signal definition
 * runs at two operating points over one persisted corpus: the worker stamps a confidence band on
 * every detection (strong phrase -> HIGH, weak phrase -> LOW), then the mode is a read-time filter.
 * Discovery surfaces both bands (high recall); tracking surfaces only HIGH (high precision); the
 * metrics endpoint surfaces the differing recall/precision per mode. Toggling the mode never loses
 * history. Runs against the real pgvector Postgres (Testcontainers) so the signal schema applies for
 * real.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class ClassifierPrecisionModeIntegrationTest {

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

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

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

    @Test
    void oneSignalTwoModes_differingPrecisionRecallSurfaced() {
        // Frustration is off by default and needs an explicit capability grant; this test is about
        // frustration's own precision-mode behavior, so it grants the capability before the project
        // is created (the moment seeding reads it).
        String pid = TenantFixture.bootstrap(
                        tenants, "signal-modes", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        Instant base = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration skips a conversation opener; seed the preamble so the turn under test is scoreable.
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, base.toString());

        // Both turns live in one session, each its own trace, which is what "two turns" means
        // structurally and what the turn-grain sweep draws candidates at, so the encoder classifier
        // scores each against the thread so far. The weak turn is scored first (its thread is just
        // itself, so LOW); the later strong turn's thread carries the weak turn as context but still
        // scores HIGH on its own escalation phrase.
        // Frustration's subject is the turn, and the turn is the trace, so each assertion below
        // matches on the turn's trace id, not a span inside it.
        String weakTurn = insertTurn(pid, sessionId, "the output was a bit frustrating to read", base);
        String strongTurn =
                insertTurn(pid, sessionId, "This is frustrating, you're not listening to me", base.plusSeconds(1));

        service.seedBuiltIns(pid); // seed the catalog so the definition is addressable up front
        ClassifierRow frustration = signals.findByKey(pid, "frustration").orElseThrow();
        awaitSignalEvents(pid, frustration.id(), 2);

        // Frustration seeds at TRACKING: it's the one built-in whose bands have been characterized
        // in production, so it doesn't start at the high-recall default the other built-ins take.
        // The mode is still a read-time filter, which is what the rest of this test exercises: both
        // bands are persisted regardless, and each assertion below asks for the band it wants. See
        // BuiltInClassifierCatalog's TRACKING default, the only place this fact now lives.
        assertEquals(ClassifierRow.Mode.TRACKING, frustration.mode(), "frustration seeds at the tracking bar");
        List<ClassifierDtos.ClassifierEventView> discovery =
                service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.DISCOVERY, 100);
        assertEquals(2, discovery.size(), "discovery surfaces both the strong and weak hits (recall)");

        // Tracking (high precision): only the HIGH-confidence hit surfaces.
        List<ClassifierDtos.ClassifierEventView> tracking =
                service.eventsForClassifier(pid, frustration.id(), ClassifierRow.Mode.TRACKING, 100);
        assertEquals(1, tracking.size(), "tracking surfaces only the HIGH-confidence hit (precision)");
        assertEquals(strongTurn, tracking.get(0).subjectId(), "the strong-phrase turn is the precise hit");
        assertEquals(Detection.Confidence.HIGH, tracking.get(0).confidence());
        assertTrue(
                discovery.stream()
                        .anyMatch(
                                e -> weakTurn.equals(e.subjectId()) && Detection.Confidence.LOW.equals(e.confidence())),
                "the weak-phrase observation is the LOW-confidence hit discovery adds over tracking");

        // The per-mode metrics surface the differing precision/recall from the one corpus.
        ClassifierService.ClassifierMetrics m = service.metrics(pid, frustration.id());
        assertEquals(2, m.discoveryFired(), "discovery fired count");
        assertEquals(1, m.trackingFired(), "tracking fired count");
        assertEquals(1, m.lowConfidence(), "the recall delta discovery buys over tracking");

        // Flipping the mode is a definition-state change that never loses history (read-time gate).
        // Flipped toward DISCOVERY because that is the direction that now represents a change: the
        // signal seeds at TRACKING, so re-setting it to TRACKING would assert nothing.
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

    /** One user-facing turn: its own trace with a root llm span. Returns the turn's producer trace id. */
    private String insertTurn(String pid, String sessionId, String input, Instant at) {
        // Store the input the way ingest does, the gen_ai user envelope, so the sweep exercises
        // the real shape the encoder classifiers must unwrap, not clean text production never emits.
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

    /**
     * Drive the worker until at least {@code expected} events for the signal have landed. The sweep is
     * async (bounded executor) and a cursor advance can split a batch across rounds, so we re-tick on
     * each poll; the sweep is idempotent ({@code ON CONFLICT DO NOTHING}), so re-ticking never
     * double-counts.
     */
    private void awaitSignalEvents(String pid, String classifierId, int expected) {
        for (int i = 0; i < 50; i++) {
            if (service.eventsForClassifier(pid, classifierId, 100).size() >= expected) return;
            worker.tick();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

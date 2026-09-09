// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierDtos;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.substrate.SubstrateReadRepository.ToolErrorRate;
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
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end acceptance for the async signal sweep: with the worker enabled, the structurally-
 * cheap built-ins (Task Failure on a tool error, Frustration on a keyword) detect over the streaming
 * substrate produced by the ingest write path and persist detection {@code verdict}s, off the ingest hot
 * path, on the worker's own tick. Idempotent: a second tick over the same substrate writes no
 * duplicates. Run against the real pgvector Postgres (Testcontainers), so the signal schema + the
 * {@code FOR UPDATE SKIP LOCKED} queue run for real.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class ClassifierWorkerIntegrationTest {

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
    SubstrateReadRepository substrate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ai.tessary.detection.DetectionTableRegistry detectionTables;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void sweepDetectsToolFailureAndFrustration_idempotently() {
        // Frustration is off by default in this build; this test's whole subject is its own
        // detection behaviour, so it grants the capability before the project is created (the
        // moment seeding reads it).
        String pid = TenantFixture.bootstrap(
                        tenants, "signal-sweep", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        Instant now = Instant.now();

        // Substrate: one session, one turn (= one trace); a tool span that FAILED, and an llm span
        // whose input carries a frustration phrase.
        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration skips a conversation opener; seed the preamble so the turn under test is scoreable.
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, now.toString());
        String traceId = SubstrateV2Fixtures.traceId();

        SpanRef tool = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("tool")
                .name("search")
                .at(now)
                .payload("q", null)
                .writeRef();
        fx.toolCall(pid, tool, "search", "HTTP 500 upstream", now);

        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(now)
                .payload(ClassifierObservations.userInput("This is frustrating, you're not listening to me"), "ok")
                .write();

        service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        // frustration on the keyword; see the aggregation test below for tool_error.
        awaitEvents(pid, 1);

        List<ClassifierDtos.ClassifierEventView> all = service.events(pid, 100);
        assertTrue(all.size() >= 1, "the sweep produced the frustration event");

        ClassifierRow frustration = signals.findByKey(pid, "frustration").orElseThrow();
        List<ClassifierDtos.ClassifierEventView> fr = service.eventsForClassifier(pid, frustration.id(), 100);
        assertEquals(1, fr.size(), "Frustration fired once on the keyword");
        String frustrationSubjectId = fr.get(0).subjectId();

        // The firing lands in the classifier's OWN table, not in the shared verdict node. The subject is
        // the TURN (= the trace), and the row still carries the session it belongs to and the span the
        // detector actually read, so a reader can still get to the exact step.
        assertEquals(traceId, frustrationSubjectId, "a frustration detection's subject is the turn, i.e. the trace");
        Map<String, Object> row = jdbc.sql(
                        "SELECT classifier_key, subject_session_id, subject_trace_id, subject_span_id,"
                                + " severity, confidence FROM " + TurnGrainTestDetectionConfig.TABLE
                                + " WHERE project_id = :pid")
                .param("pid", pid)
                .query()
                .singleRow();
        assertEquals("frustration", row.get("classifier_key"));
        assertEquals(sessionId, row.get("subject_session_id"), "the detection carries the span's producer session");
        assertEquals(traceId, row.get("subject_trace_id"));
        assertNotNull(row.get("subject_span_id"), "the span the detector read rides along for deep-linking");

        // No classifier writes an automatic verdict: the `verdict` table is gone, so the claim is
        // enforced by the schema rather than by a query.

        // Idempotency: a second tick over the same substrate writes no new events.
        int before = service.events(pid, 100).size();
        service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        sleep(1_000); // give any dispatched (cursor-blocked) sweeps a chance to run
        assertEquals(before, service.events(pid, 100).size(), "re-sweeping the same observations is a no-op");
        assertEquals(
                1L,
                jdbc.sql("SELECT COUNT(*) FROM " + TurnGrainTestDetectionConfig.TABLE + " WHERE project_id = :pid")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "re-sweeping writes no duplicate detection (ON CONFLICT DO NOTHING)");

        // The detection is where the sweep stops: a flag is a filter, not evidence, so escalating it
        // needs a person, through `POST /classifiers/{id}/events/{detectionId}/analysis`. This
        // asserts the absence because the regression is silent: it does not break a request or a
        // row, it just quietly starts billing again.
        assertEquals(
                0L,
                jdbc.sql("SELECT count(*) FROM job WHERE project_id = :pid AND kind = 'grader_run'")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "a sweep must not queue a grader run on its own");
    }

    /**
     * The per-tool failure-rate aggregation reports failed/total/rate grouped by tool name over the
     * raw tool_call data.
     *
     * <p>{@code tool_error} no longer writes a per-observation detection over this data; the rate is
     * an aggregate read (the {@code vitals} slice) instead. This aggregation is the substrate both
     * surfaces read, so it keeps its coverage.
     */
    @Test
    void perToolFailureRatesAggregateOverRawToolCalls() {
        String pid =
                TenantFixture.bootstrap(tenants, "tool-error-rates").project().id();
        Instant now = Instant.now();

        String sessionId = SubstrateV2Fixtures.sessionId();
        // Frustration skips a conversation opener; seed the preamble so the turn under test is scoreable.
        ClassifierConversations.seedPriorTurn(fx, pid, sessionId, now.toString());
        String traceId = SubstrateV2Fixtures.traceId();

        // "search": one failed call + one successful call → 50% failure rate.
        SpanRef searchSpan = toolSpan(pid, traceId, sessionId, "search", "q", now);
        fx.toolCall(pid, searchSpan, "search", "HTTP 500 upstream", now);
        SpanRef searchOkSpan = toolSpan(pid, traceId, sessionId, "search", "q2", now);
        fx.toolCall(pid, searchOkSpan, "search", null, now);

        // "fetch": one failed call only → 100% failure rate.
        SpanRef fetchSpan = toolSpan(pid, traceId, sessionId, "fetch", "u", now);
        fx.toolCall(pid, fetchSpan, "fetch", "timeout", now);

        service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();

        // tool_error is a classifier again, but it still writes nothing through this worker: it has
        // no sweep and no per-observation detector, so the row exists and the tick leaves it alone.
        assertTrue(signals.findByKey(pid, "tool_error").isPresent(), "tool_error is a classifier again");
        assertEquals(
                0L,
                jdbc.sql("SELECT COUNT(*) FROM (" + detectionTables.unionSql() + ") d"
                                + " WHERE project_id = :pid AND classifier_id = 'tool_error'")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "and it writes no per-observation detection, which is what 0030 deleted the old one over");

        // Per-tool failure-rate aggregation: search 1/2 = 0.5, fetch 1/1 = 1.0.
        List<ToolErrorRate> rates = substrate.toolErrorRatesByName(pid);
        ToolErrorRate search = rateFor(rates, "search");
        assertEquals(2, search.totalCalls(), "search total calls");
        assertEquals(1, search.failedCalls(), "search failed calls");
        assertEquals(0.5, search.failureRate(), 1e-9, "search 50% failure rate");
        ToolErrorRate fetch = rateFor(rates, "fetch");
        assertEquals(1, fetch.totalCalls(), "fetch total calls");
        assertEquals(1, fetch.failedCalls(), "fetch failed calls");
        assertEquals(1.0, fetch.failureRate(), 1e-9, "fetch 100% failure rate");
    }

    private SpanRef toolSpan(String pid, String traceId, String sessionId, String name, String input, Instant at) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("tool")
                .name(name)
                .at(at)
                .payload(input, null)
                .writeRef();
    }

    private static ToolErrorRate rateFor(List<ToolErrorRate> rates, String toolName) {
        return rates.stream()
                .filter(r -> toolName.equals(r.toolName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rate row for tool " + toolName));
    }

    /** Poll up to ~10s for at least {@code expected} events on a specific signal. */
    private void awaitEventsForSignal(String pid, String classifierId, int expected) {
        for (int i = 0; i < 100; i++) {
            if (service.eventsForClassifier(pid, classifierId, 100).size() >= expected) return;
            sleep(100);
        }
    }

    /** Poll up to ~10s for the async sweep to land at least {@code expected} events. */
    private void awaitEvents(String pid, int expected) {
        for (int i = 0; i < 100; i++) {
            if (service.events(pid, 100).size() >= expected) return;
            sleep(100);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

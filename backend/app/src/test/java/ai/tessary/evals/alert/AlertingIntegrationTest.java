// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.ClassifierRepository;
import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.classifier.detector.Detection;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.storage.SessionRepository;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance for alerting: the roll-ups and case notifications that are all alerting still decides.
 * Exercised against the real pgvector Postgres (Testcontainers) so the alert schema applies for real.
 * Detections are seeded straight into a per-classifier detection table — the production alert path is
 * strictly read-only over {@code classifier} and the DetectionTableRegistry-stitched union — and the
 * {@link AlertWorker} is driven to the behavior that survived the threshold path's removal: a daily
 * digest roll-up is produced when its cron is due, exactly once per period.
 */
@SpringBootTest
class AlertingIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityService capabilities;

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
    void setUpFixtures() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Autowired
    ClassifierRepository signals;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    AlertService alertService;

    @Autowired
    AlertEventRepository alertEvents;

    @Autowired
    AlertWorker worker;

    // The three threshold tests that stood here are gone with the path they exercised. A classifier's
    // window no longer becomes an alert_event: it opens a FINDING inside the classifier's own sweep
    // (ClassifierArming), and a case_opened rule carries that to the same channels. What remains here is
    // the delivery machinery — roll-ups and case notifications — which is all alerting still decides.

    @Test
    void digestRollupProducedWhenCronDue() {
        var fix = TenantFixture.bootstrap(tenants, "alert-digest");
        String pid = fix.project().id();
        String sigA = seedSignal(pid, "frustration");
        String sigB = seedSignal(pid, "task_failure");

        // Create the schedule FIRST: the first digest's window is [rule.created_at, now), so the rolled-up
        // detections must land AFTER the rule exists (an every-second cron is due after a >1s wait).
        AlertRuleRow digestRule = alertService.upsertRule(pid, digestReq("* * * * * *"));
        seedDetection(pid, sigA, spanIn(pid, newSession(pid)));
        seedDetection(pid, sigA, spanIn(pid, newSession(pid)));
        seedDetection(pid, sigB, spanIn(pid, newSession(pid)));
        sleep(1100);
        worker.tick();

        List<AlertEventRow> fired = alertEvents.listByProject(pid, 100);
        // The threshold path didn't run (no per-signal rules), so the only firing is the digest roll-up.
        assertEquals(1, fired.size(), "a due digest produces exactly one roll-up");
        AlertEventRow digest = fired.get(0);
        assertEquals(AlertRuleRow.RuleType.DIGEST, digest.ruleType());
        assertEquals(digestRule.id(), digest.alertRuleId(), "a digest firing FKs its digest rule");
        assertEquals(3, digest.value(), "the digest rolls up all 3 detections in the window");
        assertTrue(digest.classifierId() == null, "a roll-up spans all signals");

        // The cron anchor advanced, so an immediate re-tick within the same second is not due again.
        worker.tick();
        assertEquals(1, alertEvents.listByProject(pid, 100).size(), "the anchor advanced — no duplicate digest");
    }

    // ---- request builders -------------------------------------------------------------------------

    private static AlertDtos.UpsertAlertRuleRequest digestReq(String cron) {
        return new AlertDtos.UpsertAlertRuleRequest(
                AlertRuleRow.RuleType.DIGEST,
                "daily",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                cron,
                null,
                null);
    }

    // ---- seeding helpers (the production alert path never writes these tables) ---------------------

    private String seedSignal(String pid, String key) {
        String now = Instant.now().toString();
        String id = Ids.ulid();
        signals.insert(new ClassifierRow(
                id,
                pid,
                key + "-" + id,
                key,
                null,
                "keyword",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));
        return id;
    }

    /**
     * Mint a producer session id (the "user" grain the distinct_users basis counts).
     *
     * <p>No row is written here. In v2 the count is {@code COUNT(DISTINCT span.session_id)} — the span
     * carries the session as a column — so what makes a session countable is a SPAN naming it, not the
     * existence of a session row. {@link #spanIn} writes both.
     */
    private String newSession(String pid) {
        return SubstrateV2Fixtures.sessionId();
    }

    /** The typed subject ancestry of a span-grain detection: its session, trace, and span. */
    private record Subject(String sessionId, String traceId, String spanId) {}

    /** One trace with a root llm span under an existing session; returns the detection's subject ancestry. */
    private Subject spanIn(String pid, String sessionId) {
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRef span = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(Instant.now())
                .payload("in", "out")
                .writeRef();
        return new Subject(sessionId, traceId, span.spanId());
    }

    /**
     * A classifier detection in its own table — what the digest roll-up now counts. Its {@code classifier_key}
     * is how the roll-up query resolves the signal row, with the detector's confidence band and coarse
     * severity on their own columns rather than borrowed ones.
     *
     * <p><b>{@code created_at} is passed explicitly rather than left to the column's {@code DEFAULT
     * now()}</b>, because the roll-up window's lower bound is {@code alert_rule.created_at} — a JVM
     * instant. Letting the row default to the DATABASE clock would put the two ends of one predicate on
     * two clocks separated by a few milliseconds, and the digest window this test opens is only a few
     * milliseconds wide at its start: a detection stamped by a Postgres clock even slightly behind the
     * JVM's lands before {@code windowStart} and the roll-up finds nothing. Both ends read one clock here,
     * so the assertion is about the query and not about container clock drift.
     */
    private void seedDetection(String pid, String classifierId, Subject subject) {
        String classifierKey = signals.findById(pid, classifierId).orElseThrow().classifierKey();
        jdbc.sql("INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key,"
                        + " subject_session_id, subject_trace_id, subject_span_id, severity, confidence,"
                        + " created_at)"
                        + " VALUES (:id, :pid, :sid, :key, :ses, :trace, :span, :sev, :conf,"
                        + " :at::timestamptz)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", classifierId)
                .param("key", classifierKey)
                .param("ses", subject.sessionId())
                .param("trace", subject.traceId())
                .param("span", subject.spanId())
                .param("sev", Detection.Severity.WARN)
                .param("conf", Detection.Confidence.HIGH)
                .param("at", Instant.now().toString())
                .update();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

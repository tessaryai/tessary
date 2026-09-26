// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.plan.CapabilityService;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Alerting against real Postgres: roll-ups and case notifications. Detections are seeded straight into a per-
 * classifier detection table, and {@link AlertWorker} produces a daily digest when its cron is due, once per period.
 */
@SpringBootTest
class AlertingIntegrationTest {

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

    @Autowired
    AlertRuleRepository rules;

    // Classifier windows open findings now (ClassifierArming), not alert_events; what remains here is the delivery
    // machinery.

    @Test
    void digestRollupProducedWhenCronDue() {
        var fix = TenantFixture.bootstrap(tenants, "alert-digest");
        String pid = fix.project().id();
        String sigA = seedSignal(pid, "frustration");
        String sigB = seedSignal(pid, "task_failure");

        // Rule first: the digest window starts at rule.created_at, so detections must land after it.
        AlertRuleRow digestRule = alertService.upsertRule(pid, digestReq("* * * * * *"));
        seedDetection(pid, sigA, spanIn(pid, newSession(pid)));
        seedDetection(pid, sigA, spanIn(pid, newSession(pid)));
        seedDetection(pid, sigB, spanIn(pid, newSession(pid)));
        sleep(1100);
        worker.tick();

        List<AlertEventRow> fired = alertEvents.listByProject(pid, 100);
        assertEquals(1, fired.size(), "a due digest produces exactly one roll-up");
        AlertEventRow digest = fired.get(0);
        assertEquals(AlertRuleRow.RuleType.DIGEST, digest.ruleType());
        assertEquals(digestRule.id(), digest.alertRuleId(), "a digest firing FKs its digest rule");
        assertEquals(3, digest.value(), "the digest rolls up all 3 detections in the window");
        assertTrue(digest.classifierId() == null, "a roll-up spans all signals");

        // The cron anchor advanced, so a re-tick in the same second is not due.
        worker.tick();
        assertEquals(1, alertEvents.listByProject(pid, 100).size(), "the anchor advanced — no duplicate digest");
    }

    /** A due period with nothing in it fires nothing but advances the anchor. */
    @Test
    void aDueDigestWithNoActivityAdvancesItsAnchorWithoutFiring() {
        String pid =
                TenantFixture.bootstrap(tenants, "alert-digest-empty").project().id();
        String created = Instant.now().minusSeconds(5).toString();
        String ruleId = Ids.ulid();
        rules.insert(new AlertRuleRow(
                ruleId,
                pid,
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
                "* * * * * *",
                null,
                true,
                null,
                null,
                null,
                null,
                "{}",
                created,
                created));

        worker.tick();

        assertEquals(List.of(), alertEvents.listByProject(pid, 100), "no activity, no digest");
        assertTrue(
                rules.findById(pid, ruleId).orElseThrow().lastDigestAt() != null,
                "the empty period is consumed, not re-read next tick");
    }

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
     * A producer session id. No row is written: v2 counts {@code COUNT(DISTINCT span.session_id)}, so a span naming
     * it is what counts.
     */
    private String newSession(String pid) {
        return SubstrateV2Fixtures.sessionId();
    }

    /** The typed subject ancestry of a span-grain detection: its session, trace, and span. */
    private record Subject(String sessionId, String traceId, String spanId) {}

    /** One trace with a root llm span; returns the detection's subject. */
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
     * A classifier detection in its own table, what the digest counts. {@code created_at} is passed explicitly: the
     * window's lower bound is a JVM instant, and a Postgres clock slightly behind would land the row before {@code
     * windowStart}.
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

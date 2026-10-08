// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A person's classifier reset against real Postgres: it clears exactly that classifier's output and learned
 * state, leaves ruled findings and other classifiers alone, and puts the sweep back at the start.
 */
@SpringBootTest
class ClassifierResetIntegrationTest {

    private static final String SWEPT_AT = "2026-01-01T00:00:00Z";
    private static final String SWEPT_ID = "obs-swept";

    @Autowired
    ClassifierReset reset;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    JdbcClient jdbc;

    private String sweptProject(String name) {
        String pid = TenantFixture.bootstrap(tenants, name, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        classifiers.seedBuiltIns(pid);
        for (ClassifierRow row : classifiers.list(pid)) {
            jobs.enqueue(pid, row.id(), 3600);
        }
        for (ClassifierJobRow job : jobs.claimBatch("setup-" + name, 500, 300, 5)) {
            if (job.projectId().equals(pid)) jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);
        }
        return pid;
    }

    private ClassifierRow classifier(String pid, String key) {
        return classifiers.list(pid).stream()
                .filter(c -> c.classifierKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("built-in not seeded: " + key));
    }

    private ClassifierJobRow job(String pid, ClassifierRow row) {
        return jobs.findByClassifier(pid, row.id()).orElseThrow();
    }

    private void detect(String pid, ClassifierRow row, String traceId) {
        detections.insert(
                Ids.ulid(),
                row.detector(),
                pid,
                row.id(),
                row.classifierKey(),
                null,
                null,
                traceId,
                "span-" + traceId,
                Detection.Severity.WARN,
                "high",
                null);
    }

    private long detectionCount(String table, ClassifierRow row) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE classifier_id = :sid")
                .param("sid", row.id())
                .query(Long.class)
                .single();
    }

    private String finding(String pid, String classifierKey, String cause, @Nullable String verdict) {
        String id = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                             status, onset_at, last_seen_at, created_at, updated_at,
                                             triage_verdict, triage_action)
                        VALUES (:id, :pid, :key, :cause, 'classifier', :cause, 'open', :now, :now, :now, :now,
                                :verdict, :action)
                        """)
                .param("id", id)
                .param("pid", pid)
                .param("key", classifierKey)
                .param("cause", cause)
                .param("now", now)
                .param("verdict", verdict)
                .param("action", verdict == null ? null : "opened_case")
                .update();
        return id;
    }

    private String findingStatus(String id) {
        return jdbc.sql("SELECT status FROM finding WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void rateState(String table, String pid, String callSite) {
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO " + table + " (project_id, call_site_id, state_epoch, updated_at)"
                        + " VALUES (:pid, :cs, 'epoch-1', :now)")
                .param("pid", pid)
                .param("cs", callSite)
                .param("now", now)
                .update();
    }

    private long rows(String table, String pid) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    private void metricBaseline(String pid, ClassifierRow row) {
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO metric_baseline (id, project_id, classifier_id, measure, bucket_kind, bucket_key,
                                                     created_at, updated_at)
                        VALUES (:id, :pid, :sid, 'cost', 'call_site', 'cs-1', :now, :now)
                        """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", row.id())
                .param("now", now)
                .update();
    }

    private long metricBaselines(ClassifierRow row) {
        return jdbc.sql("SELECT count(*) FROM metric_baseline WHERE classifier_id = :sid")
                .param("sid", row.id())
                .query(Long.class)
                .single();
    }

    @Test
    void reset_clearsDetectionsAndUnruledFindings_andRestartsADeadSweepFromTheStart() {
        String pid = sweptProject("reset-clears");
        ClassifierRow leak = classifier(pid, "secret_leak");
        ClassifierRow malformed = classifier(pid, "malformed_output");
        detect(pid, leak, "t-1");
        detect(pid, leak, "t-2");
        detect(pid, malformed, "t-3");
        String unruled = finding(pid, "secret_leak", "cause-unruled", null);
        String ruled = finding(pid, "secret_leak", "cause-ruled", "positive");
        String otherClassifier = finding(pid, "malformed_output", "cause-other", null);
        jdbc.sql("UPDATE job SET status = 'dead', attempts = 5, last_error = 'boom' WHERE id = :id")
                .param("id", job(pid, leak).id())
                .update();

        reset.reset(pid, leak.id(), "ops@example.com");

        assertEquals(0, detectionCount("secret_leak_detection", leak));
        assertEquals(1, detectionCount("malformed_output_detection", malformed), "another classifier keeps its own");
        assertEquals("closed", findingStatus(unruled));
        assertEquals("open", findingStatus(ruled), "a ruled finding is a decision, and it backs a case");
        assertEquals("open", findingStatus(otherClassifier));
        ClassifierJobRow restarted = job(pid, leak);
        assertEquals(ClassifierJobRow.PENDING, restarted.status());
        assertNull(restarted.cursorAt());
        assertNull(restarted.cursorId());
        assertEquals(0, restarted.attempts());
        assertNull(restarted.lastError());
        assertEquals(SWEPT_ID, job(pid, malformed).cursorId(), "another classifier's sweep does not move");
    }

    @Test
    void reset_isRefusedWhileASweepHoldsTheJob_andChangesNothing() {
        String pid = sweptProject("reset-running");
        ClassifierRow leak = classifier(pid, "secret_leak");
        detect(pid, leak, "t-1");
        jobs.enqueue(pid, leak.id(), 3600);
        jobs.claimBatch("worker-reset-running", 500, 300, 5);
        String unruled = finding(pid, "secret_leak", "cause-unruled", null);

        TessaryException refused = assertThrows(TessaryException.class, () -> reset.reset(pid, leak.id(), null));

        assertEquals(ClassifierError.SWEEP_RUNNING, refused.error());
        assertEquals(1, detectionCount("secret_leak_detection", leak));
        assertEquals("open", findingStatus(unruled));
        assertEquals(ClassifierJobRow.CLAIMED, job(pid, leak).status());
    }

    @Test
    void reset_deletesTheLearnedStateOfItsOwnKindOnly() {
        String pid = sweptProject("reset-learned-state");
        ClassifierRow frustration = classifier(pid, "frustration");
        ClassifierRow costDrift = classifier(pid, "cost_drift");
        ClassifierRow durationDrift = classifier(pid, "duration_drift");
        rateState("frustration_state", pid, "cs-1");
        rateState("groundedness_state", pid, "cs-1");
        metricBaseline(pid, costDrift);
        metricBaseline(pid, durationDrift);

        reset.reset(pid, frustration.id(), null);
        reset.reset(pid, costDrift.id(), null);

        assertEquals(0, rows("frustration_state", pid));
        assertEquals(1, rows("groundedness_state", pid), "groundedness learned its own normal");
        assertEquals(0, metricBaselines(costDrift));
        assertEquals(1, metricBaselines(durationDrift));
    }

    @Test
    void reset_clearsToolErrorReferencesAndGroundednessAssessments_andLeavesTheOtherKindAlone() {
        String pid = sweptProject("reset-tool-error-groundedness");
        ClassifierRow toolError = classifier(pid, "tool_error");
        ClassifierRow groundedness = classifier(pid, "groundedness");
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO tool_error_state (project_id, tool_key, state_epoch, updated_at)"
                        + " VALUES (:pid, 'search', 'epoch-1', :now)")
                .param("pid", pid)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO tool_error_reference (project_id, tool_key, calls, failures, accepted_at)"
                        + " VALUES (:pid, 'search', 100, 3, :now)")
                .param("pid", pid)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO groundedness_assessment (id, project_id, classifier_id, subject_trace_id,
                            subject_span_id, call_site_id, unsupported, flagged, scorer_version,
                            observation_started_at)
                        VALUES (:id, :pid, :sid, 't-1', 's-1', 'cs-1', 0.4, true, 'v1', now())
                        """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", groundedness.id())
                .update();

        reset.reset(pid, toolError.id(), null);

        assertEquals(0, rows("tool_error_state", pid));
        assertEquals(0, rows("tool_error_reference", pid), "a reference a person accepted goes too");
        assertEquals(1, rows("groundedness_assessment", pid), "tool errors own none of groundedness's work");

        reset.reset(pid, groundedness.id(), null);

        assertEquals(0, rows("groundedness_assessment", pid));
    }
}

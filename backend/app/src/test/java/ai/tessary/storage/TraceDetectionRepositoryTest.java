// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import ai.tessary.detection.DetectionTableRegistry;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The trace and session views' flags, read from the tables the registry says they show. The bugs are a hidden
 * classifier's flags marked on a trace, and a {@code detected_by} pick of a hidden classifier that lists the traces
 * it flagged.
 */
@SpringBootTest
record TraceDetectionRepositoryTest(
        JdbcClient jdbc, TenantService tenants, ClassifierRepository classifiers, TraceV2Repository traces) {

    private static final String T0 = "2026-08-01T00:00:00Z";

    /**
     * Only a table registered as shown marks a trace, and registering it shown is all it takes. The bug is a reader
     * that names its tables itself, so a newly shown table marks nothing or a hidden one marks everything.
     */
    @Test
    void aTableNotShownInTraceViewsMarksNothing() {
        String pid = project("trace-marks-hidden");
        String frustration = classifier(pid, "frustration", "Frustration");
        String secretLeak = classifier(pid, "secret_leak", "Secret Leak");
        frustrationFlag(pid, frustration, "tr-1");
        secretLeakFlag(pid, secretLeak, "tr-2");

        assertEquals(
                List.of(new TraceDetectionRepository.Mark("tr-1", "sp-tr-1", frustration, "Frustration")),
                repository(false).forTraces(pid, List.of("tr-1", "tr-2")));
        assertEquals(
                List.of(
                        new TraceDetectionRepository.Mark("tr-1", "sp-tr-1", frustration, "Frustration"),
                        new TraceDetectionRepository.Mark("tr-2", "sp-tr-2", secretLeak, "Secret Leak")),
                repository(true).forTraces(pid, List.of("tr-1", "tr-2")));
    }

    /**
     * Filtering the traces list by a classifier the views do not show matches no trace, though it flagged one; the
     * shown classifier's pick lists its trace.
     */
    @Test
    void aDetectedByPickOfAHiddenClassifierMatchesNoTrace() {
        String pid = project("trace-filter-hidden");
        String frustration = classifier(pid, "frustration", "Frustration");
        String secretLeak = classifier(pid, "secret_leak", "Secret Leak");
        seedTrace(pid, "tr-1");
        seedTrace(pid, "tr-2");
        frustrationFlag(pid, frustration, "tr-1");
        secretLeakFlag(pid, secretLeak, "tr-2");

        assertEquals(List.of(), listedBy(pid, secretLeak));
        assertEquals(List.of("tr-1"), listedBy(pid, frustration));
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** The open registry's frustration table, beside secret_leak's shown or hidden as asked. */
    private TraceDetectionRepository repository(boolean secretLeakShown) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of(
                "frustration",
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE, true, true),
                "secretLeak",
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, secretLeakShown, false)));
        return new TraceDetectionRepository(
                jdbc, new DetectionTableRegistry(beans.getBeanProvider(DetectionTable.class)));
    }

    private List<String> listedBy(String pid, String classifierId) {
        var query = new TraceV2Repository.TraceQuery(null, null, null, null, null, null, null, null, classifierId);
        return traces.list(pid, query, null, 10, null, null, null).stream()
                .map(TraceV2Repository.Summary::id)
                .toList();
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private String classifier(String pid, String detector, String name) {
        return ClassifierRows.insert(classifiers, pid, "k-" + detector, name, detector, null);
    }

    private void seedTrace(String pid, String traceId) {
        jdbc.sql("""
            INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
            VALUES (:pid, :sid, CAST(:t0 AS timestamptz), CAST(:t0 AS timestamptz),
                    CAST(:t0 AS timestamptz))
            """)
                .param("pid", pid)
                .param("sid", "s-" + traceId)
                .param("t0", T0)
                .update();
        jdbc.sql("""
            INSERT INTO trace (project_id, id, session_id, started_at, event_ts)
            VALUES (:pid, :id, :sid, CAST(:t0 AS timestamptz), CAST(:t0 AS timestamptz))
            """)
                .param("pid", pid)
                .param("id", traceId)
                .param("sid", "s-" + traceId)
                .param("t0", T0)
                .update();
    }

    private void frustrationFlag(String pid, String classifierId, String traceId) {
        jdbc.sql("""
            INSERT INTO frustration_detection (id, project_id, classifier_id, classifier_key,
                subject_session_id, subject_trace_id, subject_span_id, subject_started_at, severity,
                confidence)
            VALUES (:id, :pid, :cid, 'frustration', :sid, :tid, :spid, CAST(:t0 AS timestamptz),
                    'warn', 'high')
            """)
                .param("id", pid + "-fd-" + traceId)
                .param("pid", pid)
                .param("cid", classifierId)
                .param("sid", "s-" + traceId)
                .param("tid", traceId)
                .param("spid", "sp-" + traceId)
                .param("t0", T0)
                .update();
    }

    private void secretLeakFlag(String pid, String classifierId, String traceId) {
        jdbc.sql("""
            INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key,
                subject_session_id, subject_trace_id, subject_span_id, subject_started_at, severity,
                confidence)
            VALUES (:id, :pid, :cid, 'secret_leak', :sid, :tid, :spid, CAST(:t0 AS timestamptz),
                    'critical', 'high')
            """)
                .param("id", pid + "-sl-" + traceId)
                .param("pid", pid)
                .param("cid", classifierId)
                .param("sid", "s-" + traceId)
                .param("tid", traceId)
                .param("spid", "sp-" + traceId)
                .param("t0", T0)
                .update();
    }
}

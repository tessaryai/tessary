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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The trace and session views' flags, read from the tables the registry says they show. The bugs are a hidden
 * classifier's flags marked on a trace, and a {@code detected_by} pick of a hidden classifier that lists the traces
 * it flagged.
 */
@SpringBootTest
record TraceDetectionRepositoryTest(
        JdbcClient jdbc,
        NamedParameterJdbcTemplate named,
        TenantService tenants,
        ClassifierRepository classifiers,
        TraceV2Repository traces,
        SpanPayloadRepository payloads,
        TraceSearchProperties search) {

    private static final String T0 = "2026-08-01T00:00:00Z";

    /**
     * Only a table registered as shown marks a trace, and registering it shown is all it takes. The bug is a reader
     * that names its tables itself, so a newly shown table marks nothing or a hidden one marks everything. Each mark
     * carries its table's grain, so a view can tell a judged turn from a judged span.
     */
    @Test
    void aTableNotShownInTraceViewsMarksNothing() {
        String pid = project("trace-marks-hidden");
        String frustration = classifier(pid, "frustration", "Frustration");
        String malformed = classifier(pid, "malformed_output", "Malformed Output");
        flag("frustration_detection", pid, frustration, "tr-1");
        flag("malformed_output_detection", pid, malformed, "tr-2");

        var turn = new TraceDetectionRepository.Mark("tr-1", "sp-tr-1", frustration, "Frustration", Grain.TRACE);
        assertEquals(List.of(turn), repository(false).forTraces(pid, List.of("tr-1", "tr-2")));
        assertEquals(
                List.of(
                        turn,
                        new TraceDetectionRepository.Mark(
                                "tr-2", "sp-tr-2", malformed, "Malformed Output", Grain.SPAN)),
                repository(true).forTraces(pid, List.of("tr-1", "tr-2")));
    }

    /**
     * Read through the open registry: a Secret Leak pick lists the trace that leaked, and a pick of a classifier the
     * views do not show matches no trace, though it flagged one. The bugs are Secret Leak dropped from the shown
     * tables, and a hidden table that filters.
     */
    @Test
    void aDetectedByPickListsAShownClassifiersTracesOnly() {
        String pid = project("trace-filter-hidden");
        String secretLeak = classifier(pid, "secret_leak", "Secret Leak");
        String malformed = classifier(pid, "malformed_output", "Malformed Output");
        seedTrace(pid, "tr-1");
        seedTrace(pid, "tr-2");
        flag("secret_leak_detection", pid, secretLeak, "tr-1");
        flag("malformed_output_detection", pid, malformed, "tr-2");

        assertEquals(List.of("tr-1"), listedBy(pid, secretLeak));
        assertEquals(List.of(), listedBy(pid, malformed));
    }

    /**
     * A pick of several classifiers keeps a trace only when every one of them flagged it, and a pick the project has no
     * classifier for keeps nothing. The bugs are picks joined with OR, and two classifiers of one table read as a
     * single {@code IN} that either one satisfies.
     */
    @Test
    void aPickOfSeveralKeepsOnlyTracesEveryOneFlagged() {
        String pid = project("trace-filter-all-of");
        String frustration = classifier(pid, "frustration", "Frustration");
        String malformedA = classifier(pid, "k-malformed-a", "malformed_output", "Malformed A");
        String malformedB = classifier(pid, "k-malformed-b", "malformed_output", "Malformed B");
        for (String trace : List.of("tr-both", "tr-frustration", "tr-malformed", "tr-two-malformed")) {
            seedTrace(pid, trace);
        }
        flag("frustration_detection", pid, frustration, "tr-both");
        flag("frustration_detection", pid, frustration, "tr-frustration");
        flag("malformed_output_detection", pid, malformedA, "tr-both");
        flag("malformed_output_detection", pid, malformedA, "tr-malformed");
        flag("malformed_output_detection", pid, malformedA, "tr-two-malformed");
        flag("malformed_output_detection", pid, malformedB, "tr-two-malformed");

        var shown = new TraceV2Repository(jdbc, named, new TraceFilters(repository(true), payloads, search));
        assertEquals(List.of("tr-both"), listedBy(shown, pid, frustration, malformedA));
        assertEquals(List.of("tr-two-malformed"), listedBy(shown, pid, malformedA, malformedB));
        assertEquals(List.of(), listedBy(shown, pid, frustration, "no-such-classifier"));
    }

    /** An empty traces page or sessions page has no flags. The bug is an {@code IN ()} that Postgres rejects. */
    @Test
    void anEmptyPageReadsNoFlags() {
        assertEquals(List.of(), repository(true).forTraces("p", List.of()));
        assertEquals(List.of(), repository(true).forSessions("p", List.of()));
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** The open registry's frustration table, beside malformed_output's shown or hidden as asked. */
    private TraceDetectionRepository repository(boolean malformedShown) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of(
                "frustration",
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE, true, true),
                "malformed",
                new DetectionTable(
                        "malformed_output", "malformed_output_detection", Grain.SPAN, malformedShown, false)));
        return new TraceDetectionRepository(
                jdbc, new DetectionTableRegistry(beans.getBeanProvider(DetectionTable.class)));
    }

    private List<String> listedBy(String pid, String classifierId) {
        return listedBy(traces, pid, classifierId);
    }

    private static List<String> listedBy(TraceV2Repository repo, String pid, String... classifierIds) {
        var query = new TraceV2Repository.TraceQuery(
                null, null, null, null, null, null, null, null, List.of(classifierIds));
        return repo.list(pid, query, null, 10, null, null, null).stream()
                .map(TraceV2Repository.Summary::id)
                .toList();
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private String classifier(String pid, String detector, String name) {
        return classifier(pid, "k-" + detector, detector, name);
    }

    private String classifier(String pid, String key, String detector, String name) {
        return ClassifierRows.insert(classifiers, pid, key, name, detector, null);
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

    /** One high-confidence detection of the trace's {@code sp-<trace>} span, in a table every open kind shares the columns of. */
    private void flag(String table, String pid, String classifierId, String traceId) {
        jdbc.sql("""
            INSERT INTO {table} (id, project_id, classifier_id, classifier_key,
                subject_session_id, subject_trace_id, subject_span_id, subject_started_at, severity,
                confidence)
            VALUES (:id, :pid, :cid, 'k', :sid, :tid, :spid, CAST(:t0 AS timestamptz), 'warn', 'high')
            """.replace("{table}", table))
                .param("id", pid + "-" + classifierId + "-" + traceId)
                .param("pid", pid)
                .param("cid", classifierId)
                .param("sid", "s-" + traceId)
                .param("tid", traceId)
                .param("spid", "sp-" + traceId)
                .param("t0", T0)
                .update();
    }
}

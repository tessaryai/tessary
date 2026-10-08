// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.traces.SessionReadService;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Detected by reads at volume: 2,000,000 traces in 200,000 sessions, once with a flag on one trace in twenty and
 * once on just 200 traces in the whole project. A rare flag is the case a keyset walk down {@code trace} gets wrong: it
 * reads most of the project before it finds a page. Each read runs through the real repository or service and must
 * finish inside {@link #READ_BUDGET_MS} after one warm-up call.
 *
 * <p>Manual, like {@code FrustrationAssessmentQueryPlanIT}: {@code DETECTION_PLAN_IT=1 mvn -f backend/pom.xml -pl app
 * -am test -Dtest=TraceDetectionQueryPlanIT -Dsurefire.failIfNoSpecifiedTests=false}. {@code DETECTION_PLAN_SCALE=10}
 * divides the volume for a small Docker disk.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DETECTION_PLAN_IT", matches = ".+")
class TraceDetectionQueryPlanIT {

    private static final Logger log = LoggerFactory.getLogger(TraceDetectionQueryPlanIT.class);

    /** One page of a list, warm. A page is 50 rows; anything near this means a scan. */
    static final long READ_BUDGET_MS = 300;

    private static final String T0 = "2026-08-01T00:00:00Z";
    private static final int PAGE = 50;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    TraceDetectionRepository detections;

    @Autowired
    SessionReadService sessions;

    @Test
    void theDetectedByReadsStayInsideTheirBudgetAtEveryDensity() {
        String pid = TenantFixture.bootstrap(
                        tenants, "detected-by-plan", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        classifierService.seedBuiltIns(pid);
        ClassifierRow frustration =
                ClassifierRows.byKey(classifiers, pid, "frustration").orElseThrow();
        long traceCount = 2_000_000L / scale();
        seedTraces(pid, traceCount);
        var byClassifier =
                new TraceV2Repository.TraceQuery(null, null, null, null, null, null, null, null, frustration.id());
        var any = new TraceV2Repository.TraceQuery(
                null, null, null, null, null, null, null, null, TraceDetectionRepository.ANY);

        for (long every : List.of(20L, traceCount / 200)) {
            seedFlags(pid, frustration.id(), traceCount, every);
            List<TraceV2Repository.Summary> page = timed(
                    "traces by classifier, 1 in " + every,
                    () -> traces.list(pid, byClassifier, null, PAGE + 1, null, null, null));
            assertEquals(PAGE + 1, page.size(), "the seed holds more than a page of flagged traces");
            timed(
                    "traces by any detection, 1 in " + every,
                    () -> traces.list(pid, any, null, PAGE + 1, null, null, null));
            timed("sessions by classifier, 1 in " + every, () -> sessions.page(pid, PAGE, null, true, byClassifier));
            List<String> unfilteredPage =
                    traces.list(pid, TraceV2Repository.TraceQuery.NONE, null, PAGE, null, null, null).stream()
                            .map(TraceV2Repository.Summary::id)
                            .toList();
            timed("marks for a page, 1 in " + every, () -> detections.forTraces(pid, unfilteredPage));
        }
    }

    private <T> T timed(String what, Supplier<T> read) {
        var warm = read.get();
        log.debug("warmed {}: {}", what, warm == null ? "nothing" : "a result");
        long start = System.nanoTime();
        T result = read.get();
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("{} took {} ms", what, ms);
        assertTrue(ms < READ_BUDGET_MS, what + " took " + ms + " ms");
        return result;
    }

    /** Ten top-level traces per session. */
    private void seedTraces(String pid, long traceCount) {
        String step = (200 * scale()) + " milliseconds";
        jdbc.sql("""
                        INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                        SELECT :pid, 's-' || g,
                               CAST(:t0 AS timestamptz) + (g * 10) * CAST(:step AS interval),
                               CAST(:t0 AS timestamptz) + (g * 10 + 9) * CAST(:step AS interval),
                               CAST(:t0 AS timestamptz) + (g * 10 + 9) * CAST(:step AS interval)
                          FROM generate_series(0, :sessions - 1) g
                        """)
                .param("pid", pid)
                .param("t0", T0)
                .param("step", step)
                .param("sessions", traceCount / 10)
                .update();
        jdbc.sql("""
                        INSERT INTO trace (project_id, id, session_id, started_at, event_ts)
                        SELECT :pid, 'tr-' || g, 's-' || (g / 10),
                               CAST(:t0 AS timestamptz) + g * CAST(:step AS interval),
                               CAST(:t0 AS timestamptz) + g * CAST(:step AS interval)
                          FROM generate_series(0, :traces - 1) g
                        """)
                .param("pid", pid)
                .param("t0", T0)
                .param("step", step)
                .param("traces", traceCount)
                .update();
        for (String table : List.of("session", "trace")) {
            jdbc.sql("ANALYZE " + table).update();
        }
    }

    /** Replaces the project's flags with one on every {@code every}-th trace. */
    private void seedFlags(String pid, String cid, long traceCount, long every) {
        jdbc.sql("DELETE FROM frustration_detection WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        jdbc.sql("""
                        INSERT INTO frustration_detection (id, project_id, classifier_id, classifier_key,
                            subject_session_id, subject_trace_id, subject_span_id, subject_started_at, severity,
                            confidence)
                        SELECT 'fd-' || g, :pid, :cid, 'frustration', 's-' || (g / 10), 'tr-' || g, 'sp-' || g,
                               CAST(:t0 AS timestamptz) + g * CAST(:step AS interval), 'warn', 'high'
                          FROM generate_series(0, :traces - 1) g
                         WHERE g % :every = 0
                        """)
                .param("pid", pid)
                .param("cid", cid)
                .param("t0", T0)
                .param("step", (200 * scale()) + " milliseconds")
                .param("traces", traceCount)
                .param("every", every)
                .update();
        jdbc.sql("ANALYZE frustration_detection").update();
    }

    /** Divides every volume. The read shapes hold at any scale; the budget is for the full one. */
    private static long scale() {
        String raw = System.getenv("DETECTION_PLAN_SCALE");
        return raw == null || raw.isBlank() ? 1 : Math.max(1, Long.parseLong(raw.trim()));
    }
}

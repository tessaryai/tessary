// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The frustration query plans at volume: 1,000,000 assessments over 200,000 conversations and 10,000,000 traces, then
 * {@code EXPLAIN (ANALYZE, BUFFERS)} on the two reads that scale.
 *
 * <p>Frustrated turns in a range joined to {@code trace} use the {@code WHERE frustrated} partial index and key
 * lookups, with no sequential scan and no {@code request}/{@code response} read. The 28-day replay ({@link
 * FrustrationRateRepository#HOURLY_TALLIES}) finishes inside {@link #REPLAY_BUDGET_MS}; its scan choice is logged,
 * not asserted, since one project's window is most of the table.
 *
 * <p>Manual, like the {@code *LiveIT} tests: {@code FRUSTRATION_PLAN_IT=1 mvn -f backend/pom.xml -pl app -am test
 * -Dtest=FrustrationAssessmentQueryPlanIT -Dsurefire.failIfNoSpecifiedTests=false}. {@code FRUSTRATION_PLAN_SCALE=10}
 * divides the volume for a small Docker disk.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "FRUSTRATION_PLAN_IT", matches = ".+")
class FrustrationAssessmentQueryPlanIT {

    private static final Logger log = LoggerFactory.getLogger(FrustrationAssessmentQueryPlanIT.class);

    /** The cold replay budget. A tenth of the volume took 0.3 s; this allows ten times that plus headroom. */
    static final long REPLAY_BUDGET_MS = 5_000;

    private static final String VERSION = "plan-it-v1";
    private static final String T0 = "2026-08-01T00:00:00Z";

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

    @Test
    void theFrustrationReadsUseTheirIndexesAtVolume() {
        String pid = TenantFixture.bootstrap(
                        tenants, "fr-plan", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        classifierService.seedBuiltIns(pid);
        ClassifierRow signal =
                ClassifierRows.byKey(classifiers, pid, "frustration").orElseThrow();
        seed(pid, signal.id());

        String range = explain(String.format(Locale.ROOT, """
                SELECT a.trace_id, a.conversation_id, a.call_site_id, a.turn_started_at, t.started_at
                  FROM frustration_assessment a
                  JOIN trace t ON t.project_id = a.project_id AND t.id = a.trace_id
                 WHERE a.project_id = '%s' AND a.classifier_id = '%s' AND a.frustrated
                   AND a.turn_started_at >= '%s'::timestamptz + interval '20 days'
                   AND a.turn_started_at < '%s'::timestamptz + interval '21 days'
                 ORDER BY a.turn_started_at DESC
                """, pid, signal.id(), T0, T0));
        log.info("frustrated-range plan:\n{}", range);
        assertTrue(range.contains("ix_frustration_assessment_frustrated"), "the partial index drives it");
        assertFalse(range.contains("Seq Scan on frustration_assessment"));
        assertFalse(range.contains("Seq Scan on trace"));

        String replay = explain(FrustrationRateRepository.HOURLY_TALLIES
                .replace("{detections}", "frustration_detection")
                .replace(":pid", "'" + pid + "'")
                .replace(":cid", "'" + signal.id() + "'")
                .replace(":scorerVersion", "'" + VERSION + "'")
                .replace(":from", "'" + T0 + "'::timestamptz"));
        log.info("replay plan:\n{}", replay);
        double ms = executionMs(replay);
        assertTrue(ms < REPLAY_BUDGET_MS, "replay took " + ms + " ms");
    }

    /**
     * Every tenth trace is a scored turn: five-turn conversations over 23 days and 50 call sites, one in twenty
     * flagged on its last turn.
     */
    private void seed(String pid, String cid) {
        long scale = scale();
        long traces = 10_000_000L / scale;
        long turns = 1_000_000L / scale;
        long conversations = 200_000L / scale;
        String step = (200 * scale) + " milliseconds";
        jdbc.sql("""
                        INSERT INTO trace (project_id, id, started_at, event_ts)
                        SELECT :pid, 'tr-' || g, CAST(:t0 AS timestamptz) + g * CAST(:step AS interval),
                               CAST(:t0 AS timestamptz) + g * CAST(:step AS interval)
                          FROM generate_series(1, :traces) g
                        """)
                .param("pid", pid)
                .param("t0", T0)
                .param("step", step)
                .param("traces", traces)
                .update();
        jdbc.sql("""
                        INSERT INTO frustration_assessment (id, project_id, classifier_id, trace_id, span_id,
                            conversation_id, call_site_id, turn_started_at, frustrated, scorer_version, provider,
                            model, request, response)
                        SELECT 'fa-' || g, :pid, :cid, 'tr-' || (g * 10), 'sp-' || g,
                               'conv-' || (g % :conversations), 'cs-' || (g % :conversations % 50),
                               CAST(:t0 AS timestamptz) + g * 10 * CAST(:step AS interval),
                               (g % :conversations) % 20 = 0 AND g > :conversations * 4, :version, 'TYPESAFE', 'jev',
                               jsonb_build_object('state', repeat('x', 200)), '{}'::jsonb
                          FROM generate_series(1, :turns) g
                        """)
                .param("pid", pid)
                .param("cid", cid)
                .param("t0", T0)
                .param("step", step)
                .param("conversations", conversations)
                .param("turns", turns)
                .param("version", VERSION)
                .update();
        jdbc.sql("""
                        INSERT INTO frustration_detection (id, project_id, classifier_id, classifier_key,
                            subject_session_id, subject_trace_id, subject_started_at, severity, confidence)
                        SELECT 'fd-' || a.id, a.project_id, a.classifier_id, 'frustration', a.conversation_id,
                               a.trace_id, a.turn_started_at, 'warn', 'high'
                          FROM frustration_assessment a
                         WHERE a.project_id = :pid AND a.frustrated
                        """).param("pid", pid).update();
        for (String table : List.of("trace", "frustration_assessment", "frustration_detection")) {
            jdbc.sql("ANALYZE " + table).update();
        }
    }

    /** Divides every volume. The plan shapes hold at any scale; the budget is for the full one. */
    private static long scale() {
        String raw = System.getenv("FRUSTRATION_PLAN_SCALE");
        return raw == null || raw.isBlank() ? 1 : Math.max(1, Long.parseLong(raw.trim()));
    }

    private String explain(String sql) {
        return String.join(
                "\n",
                jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
                        .query(String.class)
                        .list());
    }

    private static double executionMs(String plan) {
        for (String line : plan.split("\n")) {
            if (line.startsWith("Execution Time:")) {
                return Double.parseDouble(line.replaceAll("[^0-9.]", ""));
            }
        }
        throw new AssertionError("no execution time in the plan");
    }
}

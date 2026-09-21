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
 * The query plans behind the frustration tables at volume: 1,000,000 assessments across 200,000 conversations
 * and 10,000,000 traces, then {@code EXPLAIN (ANALYZE, BUFFERS)} on the two reads that scale with them.
 *
 * <ul>
 *   <li>Frustrated turns in a time range joined to {@code trace} (the shape the finding's evidence and the
 *       substrate filters read): the {@code WHERE frustrated} partial index plus {@code trace} key lookups, no
 *       sequential scan on either table, and {@code request}/{@code response} never selected.
 *   <li>The 28-day replay aggregate ({@link FrustrationRateRepository#HOURLY_TALLIES}) against the real
 *       {@code frustration_detection}: the whole plan finishes inside {@link #REPLAY_BUDGET_MS}. Its scan choice is
 *       logged, not asserted: when the window holds most of the table, as it does here with one project, a
 *       sequential scan is the planner's right answer, and the turn-time index only wins once a project's window
 *       is a small share of a multi-tenant table.
 * </ul>
 *
 * <p>Manual, like the {@code *LiveIT} tests: seeding takes minutes, so it runs only when asked, on the
 * Testcontainers Postgres every integration test uses:
 *
 * <pre>
 *   FRUSTRATION_PLAN_IT=1 mvn -f backend/pom.xml -pl app -am test \
 *     -Dtest=FrustrationAssessmentQueryPlanIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * The full volume needs several GB free in Docker's disk; {@code FRUSTRATION_PLAN_SCALE=10} divides it.
 *
 * A conversation table or materialized view is added only if this or production shows the replay is slow.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "FRUSTRATION_PLAN_IT", matches = ".+")
class FrustrationAssessmentQueryPlanIT {

    private static final Logger log = LoggerFactory.getLogger(FrustrationAssessmentQueryPlanIT.class);

    /**
     * The replay over 1,000,000 assessments, cold, on a laptop's Testcontainers Postgres. The first run, at a
     * tenth of the volume, took 0.3 s; this allows the full volume ten times that plus headroom. A regression
     * past it is the reason to add the conversation view.
     */
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
        ClassifierRow signal = classifiers.findByKey(pid, "frustration").orElseThrow();
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
     * 10,000,000 traces; every tenth is a scored turn, so 1,000,000 assessments over 200,000 conversations of five
     * turns each, spread over 23 days across 50 call sites; one conversation in twenty is flagged on its last turn.
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

    /**
     * {@code FRUSTRATION_PLAN_SCALE} divides every volume, for a Docker disk too small for 10,000,000 traces. The
     * plan-shape assertions hold at any scale; the budget is stated for the full one.
     */
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

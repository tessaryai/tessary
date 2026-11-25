// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.retention.RetentionResolver;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The hit sweep and the daily reconcile against real Postgres. A hit lost, counted twice or kept after its evidence is
 * gone moves a user ID between the hit arm and the control arm of every issue estimate.
 */
@SpringBootTest
record HitSweepServiceTest(
        TenantService tenants,
        JdbcClient jdbc,
        EndUserHitRepository hits,
        FindingHitRebuilder rebuilder,
        EndUserProjectRepository projects,
        RetentionResolver retention,
        HitReads reads,
        IssueLineages lineages) {

    private static final Instant NOW = Instant.parse("2026-06-15T03:30:00Z");
    private static final int TODAY = 20619;
    private static final String CREATED = "2026-06-15T03:00:00Z";

    @Test
    void groundedness_countsAnAnswerOnce() {
        String pid = project("hits-groundedness");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.tracedSpan("t1", "s1", "user-1", "cs-a", at(TODAY - 1, 10), at(TODAY - 1, 10), "answer");
        seed.finding("f-g", "groundedness", "{\"cause_kind\":\"groundedness_rate\"}");
        seed.evidence("f-g", "witness", "t1", null, CREATED);
        seed.evidence("f-g", "witness", "t1", "s1", CREATED);

        sweep(pid, NOW);

        assertAll(
                () -> assertEquals(1, reads.findingHits(pid, "f-g").hitRefs()),
                () -> assertEquals(List.of("1|0|0|true|groundedness"), summary(pid, "f-g")));
    }

    @Test
    void frustration_hitIsWitness_notMember() {
        String pid = project("hits-frustration");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.finding("f-fr", "frustration", "{\"cause_kind\":\"frustration_rate\"}");
        for (int i = 0; i < 10; i++) {
            seed.trace("t" + i, null, "user-" + i, at(TODAY - 2, i));
            seed.evidence("f-fr", "member", "t" + i, null, CREATED);
        }
        for (int i = 0; i < 3; i++) seed.evidence("f-fr", "witness", "t" + i, null, CREATED);

        sweep(pid, NOW);
        HitReads.FindingHits read = reads.findingHits(pid, "f-fr");

        assertAll(
                () -> assertEquals(3, read.hitRefs()),
                () -> assertEquals(10, read.memberRefs(), "the reach is every scored turn"),
                () -> assertEquals(HitRule.Reach.MEMBER, read.rule().reach()),
                () -> assertEquals(List.of("3|0|0|true|frustration"), summary(pid, "f-fr")));
    }

    /** The bug: a metric shift keeps no hit row, so it can never be counted and judged like every other issue. */
    @Test
    void metricDrift_storesItsRowsLikeEveryIssue() {
        String pid = project("hits-metric-drift");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.finding("f-d", "duration_drift", "{\"cause_kind\":\"distribution_shift\"}");
        for (int i = 0; i < 4; i++) {
            seed.trace("t" + i, null, "user-" + i, at(TODAY - 1, i));
            seed.evidence("f-d", i == 0 ? "changepoint" : "member", "t" + i, null, CREATED);
        }
        seed.caseRow("c-d", 1, "metric_drift", "metric_baseline", "base-1", "duration", false, null);
        seed.attach("f-d", "c-d", "positive");

        sweep(pid, NOW);
        IssueLineages.IssueLineage lineage = lineages.lineages(pid).get(0);

        assertAll(
                () -> assertEquals(4, count(pid, "end_user_hit"), "every evidence role is stored"),
                () -> assertEquals(
                        List.of("3|0|0|true|metric_drift"), summary(pid, "f-d"), "the 3 member refs are the hits"),
                () -> assertEquals(true, lineage.metricShift()),
                () -> assertEquals(true, lineage.complete(), "never read as hits not complete"));
    }

    @Test
    void sessionOnlyRef_resolvesToSessionUserAndLastTraceDay() {
        String pid = project("hits-session-only");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.session("sess-1", at(TODAY - 5, 9));
        seed.session("sess-gone", at(TODAY - 5, 9));
        seed.trace("t1", "sess-1", null, at(TODAY - 5, 9));
        seed.trace("t2", "sess-1", "session-user", at(TODAY - 5, 11));
        seed.trace("t3", "sess-1", "session-user", at(TODAY - 2, 8));
        seed.finding("f-s", "frustration", "{\"cause_kind\":\"frustration_rate\"}");
        seed.sessionEvidence("f-s", "witness", "sess-1", CREATED);
        seed.sessionEvidence("f-s", "witness", "sess-gone", CREATED);

        sweep(pid, NOW);

        assertAll(
                () -> assertEquals(
                        List.of("witness|" + key(pid, "session-user") + "|" + (TODAY - 2) + "|0|1"), rows(pid)),
                () -> assertEquals(
                        List.of("1|0|1|true|frustration"),
                        summary(pid, "f-s"),
                        "counted as a session-only ref, never as a ref with no user ID"));
    }

    @Test
    void evidenceCreatedAtWithoutFraction_isNotSkipped() {
        String pid = project("hits-created-at-text");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.trace("t1", null, "user-1", at(TODAY - 1, 10));
        for (String f : List.of("f-whole", "f-fraction", "f-before")) {
            seed.finding(f, "tool_error", "{\"cause_kind\":\"rate_shift\"}");
        }
        seed.evidence("f-whole", "witness", "t1", null, "2026-06-15T01:00:00Z");
        seed.evidence("f-fraction", "witness", "t1", null, "2026-06-15T01:00:00.123Z");
        seed.evidence("f-before", "witness", "t1", null, "2026-06-15T00:59:59.999Z");
        projects.setHitsWatermark(pid, Instant.parse("2026-06-15T03:00:00Z"));

        sweep(pid, NOW);

        assertEquals(
                List.of("f-fraction", "f-whole"),
                jdbc.sql("SELECT DISTINCT finding_id FROM end_user_hit WHERE project_id = :pid ORDER BY 1")
                        .param("pid", pid)
                        .query(String.class)
                        .list(),
                "both rows of the watermark's second are read; the row before it is not");
    }

    @Test
    void reconcile_removesHitWhenEvidenceRemoved_onLiveDaysOnly() {
        String pid = project("hits-reconcile");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        jdbc.sql("""
                INSERT INTO retention_policy (id, project_id, data_class, ttl_days, created_at)
                VALUES (:id, :pid, 'traces', 30, '2026-01-01T00:00:00Z')
                """).param("id", "rp-" + pid).param("pid", pid).update();
        seed.trace("t-live", null, "live-user", at(TODAY - 5, 10));
        seed.trace("t-old", null, "old-user", at(TODAY - 40, 10));
        seed.finding("f-reconcile", "tool_error", "{\"cause_kind\":\"rate_shift\"}");
        seed.evidence("f-reconcile", "witness", "t-live", null, CREATED);
        seed.evidence("f-reconcile", "witness", "t-old", null, CREATED);
        sweep(pid, NOW);
        List<String> before = rows(pid);
        jdbc.sql("DELETE FROM finding_evidence WHERE project_id = :pid")
                .param("pid", pid)
                .update();

        service(NOW.plus(Duration.ofDays(1))).reconcile(pid);

        String old = "witness|" + key(pid, "old-user") + "|" + (TODAY - 40) + "|1|1";
        String live = "witness|" + key(pid, "live-user") + "|" + (TODAY - 5) + "|1|1";
        assertAll(
                () -> assertEquals(List.of(live, old).stream().sorted().toList(), before),
                () -> assertEquals(List.of(old), rows(pid), "a day past the trace TTL keeps its rows"),
                () -> assertEquals(List.of("1|0|0|true|tool_error"), summary(pid, "f-reconcile")));
    }

    @Test
    void unknownDetectorKind_usesDefaultRule_andIsNotComplete() {
        String pid = project("hits-unknown-kind");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        for (int i = 0; i < 7; i++) seed.trace("t" + i, null, "user-" + i, at(TODAY - 1, i));
        seed.finding("f-witness", "paid_custom_rate", "{\"cause_kind\":\"custom_rate\"}");
        seed.finding("f-member", "paid_custom_rate", "{\"cause_kind\":\"custom_rate\"}");
        for (int i = 0; i < 5; i++) seed.evidence("f-witness", "member", "t" + i, null, CREATED);
        for (int i = 0; i < 2; i++) seed.evidence("f-witness", "witness", "t" + i, null, CREATED);
        for (int i = 0; i < 4; i++) seed.evidence("f-member", "member", "t" + i, null, CREATED);
        for (int i = 4; i < 7; i++) seed.evidence("f-member", "baseline", "t" + i, null, CREATED);

        sweep(pid, NOW);

        assertAll(
                () -> assertEquals(2, reads.findingHits(pid, "f-witness").hitRefs(), "witnesses when any exist"),
                () -> assertEquals(4, reads.findingHits(pid, "f-member").hitRefs(), "else the flagged roles"),
                () -> assertEquals(List.of("2|0|0|false|default"), summary(pid, "f-witness")),
                () -> assertEquals(List.of("4|0|0|false|default"), summary(pid, "f-member")));
    }

    @Test
    void sweep_twice_sameRows() {
        String pid = project("hits-twice");
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.trace("t1", null, "user-1", at(TODAY - 2, 9));
        seed.trace("t2", null, " user-1 ", at(TODAY - 2, 10));
        seed.trace("t3", null, "user-2", at(TODAY - 2, 11));
        seed.trace("t4", null, null, at(TODAY - 1, 12));
        seed.trace("t5", null, "user-2", "agent.run", "cs-b", at(TODAY - 1, 13));
        seed.finding("f-twice", "tool_error", "{\"cause_kind\":\"rate_shift\"}");
        for (String t : List.of("t1", "t2", "t3", "t4", "t5")) seed.evidence("f-twice", "member", t, null, CREATED);
        for (String t : List.of("t1", "t2", "t4")) seed.evidence("f-twice", "witness", t, null, CREATED);

        sweep(pid, NOW);
        List<String> first = rows(pid);
        List<String> firstSummary = summary(pid, "f-twice");
        sweep(pid, NOW.plus(Duration.ofHours(1)));

        String user1 = key(pid, "user-1");
        assertAll(
                () -> assertEquals(first, rows(pid)),
                () -> assertEquals(firstSummary, summary(pid, "f-twice")),
                () -> assertEquals(
                        List.of("3|1|0|true|tool_error"), firstSummary, "the trace with no user ID is one ref"),
                () -> assertEquals(
                        "witness|" + user1 + "|" + (TODAY - 2) + "|1|2",
                        first.stream()
                                .filter(r -> r.startsWith("witness|" + user1))
                                .findFirst()
                                .orElseThrow(),
                        "two spellings of one ID are one user key"));
    }

    @Test
    void sweepAndReconcile_onProjectWithNoFindings_writeNothing() {
        String pid = project("hits-empty");

        assertAll(
                () -> assertEquals(
                        new HitSweepService.Result(0, 0), service(NOW).sweep(pid)),
                () -> assertEquals(
                        new HitSweepService.Result(0, 0), service(NOW).reconcile(pid)),
                () -> assertEquals(0, count(pid, "end_user_hit_summary")));
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private HitSweepService service(Instant now) {
        return new HitSweepService(hits, rebuilder, projects, retention, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void sweep(String pid, Instant now) {
        service(now).sweep(pid);
    }

    private static Instant at(int day, int hour) {
        return Instant.ofEpochSecond(day * 86_400L + hour * 3_600L);
    }

    private String key(String pid, String userId) {
        return HexFormat.of().formatHex(EndUserKeys.userKey(projects.salt(pid), userId));
    }

    private List<String> rows(String pid) {
        return jdbc.sql("""
                SELECT role || '|' || encode(user_key, 'hex') || '|' || day || '|' || route_no || '|' || refs
                  FROM end_user_hit WHERE project_id = :pid ORDER BY 1
                """).param("pid", pid).query(String.class).list();
    }

    private List<String> summary(String pid, String findingId) {
        return jdbc.sql("""
                SELECT refs_total || '|' || refs_no_user_id || '|' || session_only_refs || '|' || complete || '|'
                       || hit_rule
                  FROM end_user_hit_summary WHERE project_id = :pid AND finding_id = :fid
                """)
                .param("pid", pid)
                .param("fid", findingId)
                .query(String.class)
                .list();
    }

    private int count(String pid, String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }
}

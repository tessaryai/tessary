// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.retention.RetentionResolver;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The daily fold against real Postgres: which days a run replaces, and what it writes. Facts outlive the traces they
 * come from, so a day replaced after retention started deleting it, or a day folded twice into different rows, changes
 * history that nothing can rebuild.
 */
@SpringBootTest
record FoldServiceTest(
        TenantService tenants,
        JdbcClient jdbc,
        FoldTraceRepository traceReads,
        DayFactsRepository dayFacts,
        RouteRegistryRepository registry,
        EndUserProjectRepository projects,
        RetentionResolver retention,
        PlatformTransactionManager transactions) {

    private static final Instant NOW = Instant.parse("2026-06-15T03:00:00Z");
    private static final int TODAY = 20619;

    @Test
    void fold_twice_sameRows() {
        Project p = TenantFixture.bootstrap(tenants, "fold-twice").project();
        trace(p, "t1", "s1", "acct:x", at(TODAY - 3, "10:00:00"));
        trace(p, "t2", "s1", "acct:x", at(TODAY - 3, "10:05:00"));
        trace(p, "t3", "s1", "acct:y", at(TODAY - 3, "10:06:00"));
        trace(p, "t4", null, null, at(TODAY - 2, "09:00:00"));
        trace(p, "t5", "s2", "usr_1", at(TODAY - 1, "23:59:59"));

        fold(NOW).fold(p.id());
        List<String> first = everyRow(p);
        fold(NOW).fold(p.id());

        assertAll(
                () -> assertEquals(first, everyRow(p)),
                () -> assertEquals(3, count(p, "end_user_day")),
                () -> assertEquals(1, count(p, "end_user_alias_edge")),
                () -> assertEquals(3, count(p, "end_user")));
    }

    @Test
    void fold_lateUserId_countedAfterRefold() {
        Project p = TenantFixture.bootstrap(tenants, "fold-late-user-id").project();
        trace(p, "t1", "s1", null, at(TODAY - 2, "10:00:00"));

        fold(NOW).fold(p.id());
        List<String> before = idDays(p, TODAY - 2);
        jdbc.sql("UPDATE trace SET user_id = 'late_1', rolled_up_at = :at WHERE project_id = :pid AND id = 't1'")
                .param("pid", p.id())
                .param("at", Timestamp.from(NOW.plusSeconds(600)))
                .update();
        fold(NOW.plusSeconds(3600)).fold(p.id());

        assertAll(
                () -> assertEquals(List.of(), before),
                () -> assertEquals(List.of(key(p, "late_1") + "|1"), idDays(p, TODAY - 2)),
                () -> assertEquals(List.of("1|0"), nullDays(p, TODAY - 2)));
    }

    @Test
    void fold_dayAtTtlEdge_notReplaced() {
        Project p = TenantFixture.bootstrap(tenants, "fold-ttl-edge").project();
        ttl(p, 90);
        Instant earlier = NOW.minus(Duration.ofDays(60));
        int edge = TODAY - 90;
        for (int d = edge; d <= edge + 2; d++) {
            trace(p, "a" + d, "s" + d, "usr_1", at(d, "12:00:00"), at(d, "12:01:00"));
        }
        fold(earlier).fold(p.id());
        List<Integer> folded = List.of(dayTraces(p, edge), dayTraces(p, edge + 1), dayTraces(p, edge + 2));
        for (int d = edge; d <= edge + 2; d++) {
            trace(p, "b" + d, "s" + d, "usr_1", at(d, "13:00:00"), earlier.plus(Duration.ofDays(1)));
        }

        fold(NOW).fold(p.id());

        assertAll(
                () -> assertEquals(List.of(1, 1, 1), folded),
                () -> assertEquals(1, dayTraces(p, edge), "the day the TTL cut falls in keeps its rows"),
                () -> assertEquals(1, dayTraces(p, edge + 1), "one day of margin inside the cut"),
                () -> assertEquals(2, dayTraces(p, edge + 2)),
                () -> assertEquals(List.of(key(p, "usr_1") + "|1"), idDays(p, edge)));
    }

    @Test
    void fold_ttlZero_hasNoEdge() {
        Project p = TenantFixture.bootstrap(tenants, "fold-ttl-zero").project();
        ttl(p, 0);
        int old = TODAY - 150;
        trace(p, "t1", "s1", "usr_1", at(old, "12:00:00"), at(old, "12:01:00"));
        fold(NOW).fold(p.id());
        int first = dayTraces(p, old);
        trace(p, "t2", "s1", "usr_1", at(old, "13:00:00"), NOW.plusSeconds(60));

        fold(NOW.plusSeconds(3600)).fold(p.id());

        assertAll(() -> assertEquals(1, first), () -> assertEquals(2, dayTraces(p, old)));
    }

    @Test
    void backfill_skipsPartialOldestDay() {
        Project p = TenantFixture.bootstrap(tenants, "fold-backfill").project();
        ttl(p, 90);
        for (int d : List.of(TODAY - 90, TODAY - 89, TODAY - 88, TODAY - 3)) {
            trace(p, "t" + d, "s" + d, "usr_1", at(d, "12:00:00"));
        }

        fold(NOW).fold(p.id());

        assertAll(
                () -> assertEquals(
                        List.of(88, TODAY - 88, TODAY - 1),
                        jdbc.sql("SELECT count(*), min(day), max(day) FROM end_user_fold_day WHERE project_id = :pid")
                                .param("pid", p.id())
                                .query((rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                                .single()),
                () -> assertEquals(
                        List.of(TODAY - 88, TODAY - 3),
                        jdbc.sql("SELECT DISTINCT day FROM end_user_day WHERE project_id = :pid ORDER BY day")
                                .param("pid", p.id())
                                .query(Integer.class)
                                .list()),
                () -> assertEquals(TODAY - 88, projects.state(p.id()).historyStartDay()));
    }

    @Test
    void fold_marksDaysLostToTheTtl_asGaps() {
        Project p = TenantFixture.bootstrap(tenants, "fold-gaps").project();
        ttl(p, 90);
        trace(p, "t1", "s1", "usr_1", at(TODAY - 101, "12:00:00"), at(TODAY - 101, "12:01:00"));
        fold(NOW.minus(Duration.ofDays(100))).fold(p.id());

        fold(NOW).fold(p.id());

        assertEquals(
                List.of(12, TODAY - 100, TODAY - 89),
                jdbc.sql("""
                        SELECT count(*), min(day), max(day) FROM end_user_fold_day
                         WHERE project_id = :pid AND gap AND traces = 0
                        """)
                        .param("pid", p.id())
                        .query((rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                        .single());
    }

    @Test
    void fold_writesEveryColumn() {
        Project p = TenantFixture.bootstrap(tenants, "fold-columns").project();
        int d = TODAY - 2;
        insert(p, "p0", "s1", null, null, "usr_7Hq2kD", "chat.turn", null, "v1", at(d, "09:00:00"), null);
        insert(p, "t1", "s1", "th1", "p0", " usr_7Hq2kD ", "chat.turn", null, "v1", at(d, "09:07:30"), "daily digest");
        insert(p, "n1", null, null, null, null, "chat.turn", null, "v1", at(d, "11:00:00"), null);
        insert(p, "a1", "s9", null, null, "acme:x", "agent.run", "cs1", null, at(d, "10:00:00"), null);
        insert(p, "a2", "s9", null, null, "acme:x", "agent.run", "cs1", null, at(d, "10:05:00"), null);
        insert(p, "a3", "s9", null, null, "acme:y", "agent.run", "cs1", null, at(d, "10:06:00"), null);

        fold(NOW).fold(p.id());

        String slot36 = "0".repeat(95 - 36) + "1" + "0".repeat(36);
        String slot40 = "0".repeat(95 - 40) + "1" + "0".repeat(40);
        long jobKey = (crc32("daily digest") << 7) | 36;
        List<String> expectedDays = new ArrayList<>(List.of(
                key(p, "usr_7Hq2kD") + "|" + d + "|2|2|2|1|1|512|" + slot36 + "|" + (crc32("s1") & 0x7FFF_FFFFL) + "|"
                        + jobKey,
                key(p, "acme:x") + "|" + d + "|1|1|2|0|0|1024|" + slot40 + "|" + (crc32("s9") & 0x7FFF_FFFFL) + "|",
                key(p, "acme:y") + "|" + d + "|1|1|1|0|0|1024|" + slot40 + "|" + (crc32("s9") & 0x7FFF_FFFFL) + "|"));
        expectedDays.sort(null);
        String acmeHash = HexFormat.of().formatHex(ShapeSignature.clusterHash(projects.salt(p.id()), "acme"));
        List<String> expectedUsers = new ArrayList<>(List.of(
                key(p, "usr_7Hq2kD") + "|usr_7Hq2kD|usr:10|||" + d + "|" + d,
                key(p, "acme:x") + "|acme:x|acme:6|" + acmeHash + "|acme|" + d + "|" + d,
                key(p, "acme:y") + "|acme:y|acme:6|" + acmeHash + "|acme|" + d + "|" + d));
        expectedUsers.sort(null);

        assertAll(
                () -> assertEquals(expectedDays, rows(p, """
                        SELECT encode(user_key, 'hex') || '|' || day || '|' || route_no || '|' || version_no || '|'
                               || traces || '|' || traces_with_input || '|' || traces_with_parent || '|' || hour_mask
                               || '|' || slot_mask::text || '|' || array_to_string(session_hashes, ';') || '|'
                               || array_to_string(job_keys, ';')
                          FROM end_user_day WHERE project_id = :pid ORDER BY 1
                        """)),
                () -> assertEquals(List.of(d + "|1|1|3|0", d + "|2|2|3|1"), rows(p, """
                        SELECT day || '|' || route_no || '|' || version_no || '|' || traces || '|' || traces_no_user_id
                          FROM end_user_day_null WHERE project_id = :pid ORDER BY 1
                        """)),
                () -> assertEquals(List.of(key(p, "acme:x") + "|" + key(p, "acme:y") + "|session|" + d), rows(p, """
                        SELECT encode(from_key, 'hex') || '|' || encode(to_key, 'hex') || '|' || via || '|' || day
                          FROM end_user_alias_edge WHERE project_id = :pid
                        """)),
                () -> assertEquals(expectedUsers, rows(p, """
                        SELECT encode(user_key, 'hex') || '|' || id_text || '|' || shape_signature || '|'
                               || coalesce(encode(cluster_hash, 'hex'), '') || '|' || coalesce(account_label, '') || '|'
                               || first_seen_day || '|' || last_seen_day
                          FROM end_user WHERE project_id = :pid ORDER BY 1
                        """)),
                () -> assertEquals(List.of(d + "|2026-06-15 03:00:00+00|6|false|false"), rows(p, """
                        SELECT day || '|' || (folded_at AT TIME ZONE 'UTC')::text || '+00|' || traces || '|' || gap
                               || '|' || outage
                          FROM end_user_fold_day WHERE project_id = :pid AND day = %d
                        """.formatted(d))),
                () -> assertEquals(List.of("1||" + d, "2|v1|" + d), rows(p, """
                        SELECT version_no || '|' || coalesce(project_version_id, '') || '|' || first_trace_day
                          FROM end_user_version WHERE project_id = :pid ORDER BY version_no
                        """)),
                () -> assertEquals(List.of("1|agent.run|cs1", "2|chat.turn|__unattributed__"), rows(p, """
                        SELECT route_no || '|' || trace_name || '|' || call_site_id
                          FROM end_user_route WHERE project_id = :pid ORDER BY route_no
                        """)));
    }

    private FoldService fold(Instant now) {
        return new FoldService(
                traceReads, dayFacts, registry, projects, retention, transactions, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void ttl(Project p, int days) {
        jdbc.sql("""
                INSERT INTO retention_policy (id, project_id, data_class, ttl_days, created_at)
                VALUES (:id, :pid, 'traces', :days, '2026-01-01T00:00:00Z')
                """)
                .param("id", "rp-" + p.id())
                .param("pid", p.id())
                .param("days", days)
                .update();
    }

    private void trace(Project p, String id, @Nullable String session, @Nullable String userId, Instant started) {
        trace(p, id, session, userId, started, started.plusSeconds(60));
    }

    private void trace(
            Project p, String id, @Nullable String session, @Nullable String userId, Instant started, Instant created) {
        insert(p, id, session, null, null, userId, "chat.turn", null, null, started, null);
        jdbc.sql("UPDATE trace SET created_at = :at WHERE project_id = :pid AND id = :id")
                .param("pid", p.id())
                .param("id", id)
                .param("at", Timestamp.from(created))
                .update();
    }

    private void insert(
            Project p,
            String id,
            @Nullable String session,
            @Nullable String thread,
            @Nullable String parent,
            @Nullable String userId,
            String name,
            @Nullable String callSite,
            @Nullable String version,
            Instant started,
            @Nullable String input) {
        if (session != null) {
            jdbc.sql("""
                    INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                    VALUES (:pid, :id, :at, :at, :at) ON CONFLICT DO NOTHING
                    """)
                    .param("pid", p.id())
                    .param("id", session)
                    .param("at", Timestamp.from(started))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO trace (project_id, id, session_id, thread_id, parent_trace_id, user_id, name, call_site_id,
                                   project_version_id, started_at, event_ts, input_preview, is_settled, created_at)
                VALUES (:pid, :id, :session, :thread, :parent, :user, :name, :cs, :version, :at, :at, :input, true,
                        :at)
                """)
                .param("pid", p.id())
                .param("id", id)
                .param("session", session)
                .param("thread", thread)
                .param("parent", parent)
                .param("user", userId)
                .param("name", name)
                .param("cs", callSite)
                .param("version", version)
                .param("at", Timestamp.from(started))
                .param("input", input)
                .update();
    }

    private static Instant at(int day, String time) {
        return Instant.parse("1970-01-01T" + time + "Z").plusSeconds(day * 86_400L);
    }

    private String key(Project p, String id) {
        return HexFormat.of().formatHex(EndUserKeys.userKey(projects.salt(p.id()), id));
    }

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private List<String> everyRow(Project p) {
        List<String> all = new ArrayList<>();
        all.addAll(rows(p, """
                SELECT encode(user_key, 'hex') || day || '|' || route_no || '|' || version_no || '|' || traces || '|'
                       || traces_with_input || '|' || traces_with_parent || '|' || hour_mask || '|' || slot_mask::text
                       || '|' || array_to_string(session_hashes, ';') || '|' || array_to_string(job_keys, ';')
                  FROM end_user_day WHERE project_id = :pid ORDER BY 1
                """));
        all.addAll(rows(p, """
                SELECT day || '|' || route_no || '|' || version_no || '|' || traces || '|' || traces_no_user_id
                  FROM end_user_day_null WHERE project_id = :pid ORDER BY 1
                """));
        all.addAll(rows(p, """
                SELECT encode(from_key, 'hex') || encode(to_key, 'hex') || via || day
                  FROM end_user_alias_edge WHERE project_id = :pid ORDER BY 1
                """));
        all.addAll(rows(p, """
                SELECT encode(user_key, 'hex') || id_text || shape_signature || coalesce(account_label, '') || '|'
                       || first_seen_day || '|' || last_seen_day
                  FROM end_user WHERE project_id = :pid ORDER BY 1
                """));
        all.addAll(rows(p, """
                SELECT day || '|' || folded_at || '|' || traces || '|' || gap || '|' || outage
                  FROM end_user_fold_day WHERE project_id = :pid ORDER BY day
                """));
        return all;
    }

    private List<String> idDays(Project p, int day) {
        return rows(p, """
                SELECT encode(user_key, 'hex') || '|' || traces FROM end_user_day
                 WHERE project_id = :pid AND day = %d ORDER BY 1
                """.formatted(day));
    }

    private List<String> nullDays(Project p, int day) {
        return rows(p, """
                SELECT traces || '|' || traces_no_user_id FROM end_user_day_null
                 WHERE project_id = :pid AND day = %d ORDER BY 1
                """.formatted(day));
    }

    private int dayTraces(Project p, int day) {
        return jdbc.sql("SELECT traces FROM end_user_fold_day WHERE project_id = :pid AND day = :day")
                .param("pid", p.id())
                .param("day", day)
                .query(Integer.class)
                .single();
    }

    private int count(Project p, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", p.id())
                .query(Integer.class)
                .single();
    }

    private List<String> rows(Project p, String sql) {
        return jdbc.sql(sql).param("pid", p.id()).query(String.class).list();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.retention.RetentionRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * How long the facts stay. They must outlive the traces they come from by the history horizon, and the readable user
 * ID must not outlive its last trace: a horizon that follows the TTL loses the history the judge needs, and a text
 * kept after its traces are gone keeps a person's ID with nothing left to show for it.
 */
@SpringBootTest
record HorizonSweepTest(
        TenantService tenants,
        JdbcClient jdbc,
        EndUserHistoryHorizon horizon,
        HorizonSweepStage sweep,
        HitsStage hits,
        FoldStage fold,
        ReconcileStage reconcile,
        LearnStage learn,
        EndUserProjectRepository projects,
        RetentionRepository retention) {

    private static final int D = 20618;
    private static final String HEX_ZERO_SLOTS = "0".repeat(96);

    @Test
    void horizon_isLargerOfTraceTtlAndHistoryDays() {
        Project short_ = TenantFixture.bootstrap(tenants, "eu-horizon-90").project();
        Project long_ = TenantFixture.bootstrap(tenants, "eu-horizon-500").project();
        Project forever = TenantFixture.bootstrap(tenants, "eu-horizon-0").project();
        ttl(short_, 90);
        ttl(long_, 500);
        ttl(forever, 0);

        assertAll(
                () -> assertEquals(OptionalInt.of(400), horizon.days(short_.id())),
                () -> assertEquals(OptionalInt.of(500), horizon.days(long_.id())),
                () -> assertEquals(OptionalInt.empty(), horizon.days(forever.id()), "a TTL of 0 keeps facts for good"));
    }

    @Test
    void sweep_deletesFactsOlderThanHorizon() {
        Project p = TenantFixture.bootstrap(tenants, "eu-sweep-horizon").project();
        ttl(p, 90);
        byte[] salt = projects.salt(p.id());
        byte[] old = EndUserKeys.userKey(salt, "usr_old");
        byte[] kept = EndUserKeys.userKey(salt, "usr_kept");
        for (int day : List.of(D - 401, D - 399)) {
            byte[] key = day == D - 401 ? old : kept;
            dayRow(p, key, day);
            exec(
                    p,
                    "INSERT INTO end_user_day_null (project_id, day, route_no, version_no, traces, traces_no_user_id)"
                            + " VALUES (:pid, " + day + ", 1, 1, 2, 1)");
            exec(
                    p,
                    "INSERT INTO end_user_alias_edge (project_id, from_key, to_key, via, day) VALUES (:pid, '\\x"
                            + hex(key) + "', '\\x" + hex(kept) + "', 'session', " + day + ")");
            exec(
                    p,
                    "INSERT INTO end_user_hit (project_id, finding_id, role, user_key, day, route_no, refs, source)"
                            + " VALUES (:pid, 'f1', 'witness', '\\x" + hex(key) + "', " + day + ", 1, 1, 'evidence')");
            exec(
                    p,
                    "INSERT INTO end_user_fold_day (project_id, day, folded_at, traces) VALUES (:pid, " + day
                            + ", now(), 3)");
        }
        user(p, old, "usr_old", D - 420, D - 401);
        user(p, kept, "usr_kept", D - 420, D - 399);

        long rows = sweep.run(p.id(), D);

        List<String> stayed = List.of(String.valueOf(D - 399));
        assertAll(
                () -> assertEquals(stayed, days(p, "end_user_day")),
                () -> assertEquals(stayed, days(p, "end_user_day_null")),
                () -> assertEquals(stayed, days(p, "end_user_alias_edge")),
                () -> assertEquals(stayed, days(p, "end_user_hit")),
                () -> assertEquals(stayed, days(p, "end_user_fold_day")),
                () -> assertEquals(
                        List.of(hex(kept)),
                        strings(p, "SELECT encode(user_key, 'hex') FROM end_user WHERE project_id = :pid"),
                        "an ID not seen within the horizon leaves"),
                () -> assertEquals(7L, rows, "5 fact rows, 1 ID and the text of the kept ID, whose traces are gone"));
    }

    @Test
    void sweep_nullsIdText_whenLastTraceIsDeleted() {
        Project p = TenantFixture.bootstrap(tenants, "eu-sweep-text").project();
        Project forever =
                TenantFixture.bootstrap(tenants, "eu-sweep-text-forever").project();
        ttl(p, 90);
        ttl(forever, 0);
        byte[] salt = projects.salt(p.id());
        trace(p, "t-gone", "usr_gone", D - 100);
        retention.deleteTraces(p.id(), dayStart(D + 1 - 90).toString(), 100);
        trace(p, "t-pinned", " usr_pinned ", D - 100);
        trace(p, "t-recent", "usr_recent", D - 5);
        user(p, EndUserKeys.userKey(salt, "usr_gone"), "usr_gone", D - 120, D - 100);
        user(p, EndUserKeys.userKey(salt, "usr_pinned"), "usr_pinned", D - 120, D - 100);
        user(p, EndUserKeys.userKey(salt, "usr_recent"), "usr_recent", D - 120, D - 5);
        user(forever, EndUserKeys.userKey(projects.salt(forever.id()), "usr_old"), "usr_old", D - 600, D - 500);

        sweep.run(p.id(), D);
        sweep.run(forever.id(), D);

        assertAll(
                () -> assertEquals(
                        List.of("usr_10|usr_pinned", "usr_10|usr_recent", "usr_8|"),
                        strings(p, """
                                SELECT shape_signature || '|' || COALESCE(id_text, '') FROM end_user
                                 WHERE project_id = :pid ORDER BY 1
                                """),
                        "the text leaves with the last trace and the shape stays; a trace kept past the TTL keeps it"),
                () -> assertEquals(
                        List.of("usr_old"),
                        strings(forever, "SELECT id_text FROM end_user WHERE project_id = :pid"),
                        "a TTL of 0 never deletes a trace, so it never clears a text"));
    }

    /** The whole daily chain runs after retention deleted a folded day's trace. */
    @Test
    void factsStayAfterRetentionDeletesTraces() {
        Project p = TenantFixture.bootstrap(tenants, "eu-facts-outlive-traces").project();
        Instant now = Instant.now();
        int today = EndUserKeys.dayIndex(now);
        trace(p, "t-old", "usr_1", today - 50);
        trace(p, "t-new", "usr_1", today - 3);
        fold.run(p.id(), today - 1);
        ttl(p, 30);
        retention.deleteTraces(p.id(), now.minus(Duration.ofDays(30)).toString(), 100);

        for (EndUsersStage stage : List.of(hits, fold, reconcile, learn, sweep)) {
            stage.run(p.id(), today - 1);
        }

        String key = hex(EndUserKeys.userKey(projects.salt(p.id()), "usr_1"));
        assertAll(
                () -> assertEquals(
                        List.of("0"),
                        strings(p, "SELECT count(*) FROM trace WHERE project_id = :pid AND id = 't-old'"),
                        "retention deleted the old trace"),
                () -> assertEquals(
                        List.of(key + "|" + (today - 50) + "|1", key + "|" + (today - 3) + "|1"), strings(p, """
                                SELECT encode(user_key, 'hex') || '|' || day || '|' || traces FROM end_user_day
                                 WHERE project_id = :pid ORDER BY day
                                """)),
                () -> assertEquals(List.of("usr_1|" + (today - 50) + "|" + (today - 3)), strings(p, """
                                SELECT id_text || '|' || first_seen_day || '|' || last_seen_day FROM end_user
                                 WHERE project_id = :pid
                                """)));
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

    private void trace(Project p, String id, String userId, int day) {
        jdbc.sql("""
                INSERT INTO trace (project_id, id, user_id, name, started_at, event_ts, is_settled, created_at)
                VALUES (:pid, :id, :user, 'chat.turn', :at, :at, true, :at)
                """)
                .param("pid", p.id())
                .param("id", id)
                .param("user", userId)
                .param("at", Timestamp.from(dayStart(day).plus(Duration.ofHours(12))))
                .update();
    }

    private void dayRow(Project p, byte[] key, int day) {
        jdbc.sql("""
                INSERT INTO end_user_day (project_id, user_key, day, route_no, version_no, traces, hour_mask, slot_mask)
                VALUES (:pid, :key, :day, 1, 1, 1, 0, CAST(:slots AS bit(96)))
                """)
                .param("pid", p.id())
                .param("key", key)
                .param("day", day)
                .param("slots", HEX_ZERO_SLOTS)
                .update();
    }

    private void user(Project p, byte[] key, String text, int firstDay, int lastDay) {
        jdbc.sql("""
                INSERT INTO end_user (project_id, user_key, id_text, shape_signature, first_seen_day, last_seen_day)
                VALUES (:pid, :key, :text, :shape, :first, :last)
                """)
                .param("pid", p.id())
                .param("key", key)
                .param("text", text)
                .param("shape", "usr_" + text.length())
                .param("first", firstDay)
                .param("last", lastDay)
                .update();
    }

    private void exec(Project p, String sql) {
        jdbc.sql(sql).param("pid", p.id()).update();
    }

    private List<String> days(Project p, String table) {
        return strings(p, "SELECT DISTINCT CAST(day AS text) FROM " + table + " WHERE project_id = :pid ORDER BY 1");
    }

    private List<String> strings(Project p, String sql) {
        return jdbc.sql(sql).param("pid", p.id()).query(String.class).list();
    }

    private static Instant dayStart(int day) {
        return Instant.ofEpochSecond(day * 86_400L);
    }

    private static String hex(byte[] key) {
        return HexFormat.of().formatHex(key);
    }
}

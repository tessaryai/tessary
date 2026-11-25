// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.job.EndUserHistoryHorizon;
import ai.tessary.endusers.job.EndUsersStage;
import ai.tessary.endusers.job.FoldStage;
import ai.tessary.endusers.job.HitsStage;
import ai.tessary.endusers.job.HorizonSweepStage;
import ai.tessary.endusers.job.JudgeStage;
import ai.tessary.endusers.job.LearnStage;
import ai.tessary.endusers.job.ReconcileStage;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.read.CountKind;
import ai.tessary.endusers.read.EndUserController;
import ai.tessary.endusers.read.EndUserDtos.EndUserCaseBlock;
import ai.tessary.endusers.read.EndUserDtos.EndUserEraseRequest;
import ai.tessary.endusers.read.EndUserDtos.EndUserHitCount;
import ai.tessary.endusers.read.EndUserDtos.EndUserOverview;
import ai.tessary.retention.ProjectPurgeWorker;
import ai.tessary.retention.RetentionRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TenantFixture.Setup;
import ai.tessary.web.ApiResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Users view from stored traces and evidence, through the hourly hit sweep and the stages of the daily job, to the
 * API, with every expected number fixed by the seed. Each stage is driven through its public {@code run} with the
 * job's day, in the order the worker runs them. Retention cuts on the wall clock, so the seed is dated from one
 * reading of it: the job's day is yesterday, and every trace is a whole number of days before it.
 */
@SpringBootTest
record EndUsersEndToEndTest(
        TenantService tenants,
        JdbcClient jdbc,
        HitsStage hits,
        FoldStage fold,
        ReconcileStage reconcile,
        LearnStage learn,
        JudgeStage judge,
        HorizonSweepStage sweep,
        EndUserController controller,
        CaseService cases,
        RetentionRepository retention,
        EndUserHistoryHorizon horizon,
        ProjectPurgeWorker purge) {

    private static final String TRACE_NAME = "chat.turn";
    private static final int CASE_SEQ = 1;
    private static final String CASE_REF = "C-1";
    private static final int RETENTION_BATCH = 1_000;
    private static final List<String> SWEPT_TABLES = List.of(
            "end_user_day",
            "end_user_day_null",
            "end_user_alias_edge",
            "end_user_hit",
            "end_user_fold_day",
            "end_user");

    /**
     * Z1. 100 traces, 10 of them with a user ID. The 80 oldest carry none and fall before the 28 days the route classes
     * are learned from, so the route is not class C (90% of its traces with no user ID) and its traffic counts. The bug:
     * the page counts user IDs it cannot follow, or the share leaves out the traces with no user ID.
     */
    @Test
    void z1_noUserId_pageRefusedWithTheShare() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-no-user-id");
        String pid = s.project().id();
        int d = jobDay();
        for (int day = d - 50; day <= d - 31; day++) {
            for (int n = 0; n < 4; n++) trace(pid, "t-old-" + day + "-" + n, null, day, 8 + n * 3);
        }
        for (int i = 1; i <= 10; i++) {
            int day = d - 21 + i;
            trace(pid, "t-anon-" + i, null, day, 9);
            trace(pid, "t-id-" + i, userId(i), day, 15);
        }

        hourly(pid, d);
        daily(pid, d);

        EndUserOverview o = data(controller.overview(owner(s), org(s), proj(s)));
        assertAll(
                () -> assertEquals(PageState.NO_USER_ID, o.pageState()),
                () -> assertEquals(PageStateReason.LOW_ID_SHARE, o.pageStateReason()),
                () -> assertEquals(Map.of("ident_share", 0.1), rounded(o.reasonNumbers())),
                () -> assertEquals(d, o.asOf()),
                () -> assertNull(o.stages(), "a refused page shows no stage numbers"));
    }

    /**
     * Z2. 30 user IDs whose first traces are 14 days before the job's day. The bug: a young project is judged, or the
     * refusal loses the days and the IDs it is waiting on.
     */
    @Test
    void z2_tooEarly_namesDaysAndIds() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-too-early");
        String pid = s.project().id();
        int d = jobDay();
        for (int i = 1; i <= 30; i++) {
            trace(pid, "t-first-" + i, userId(i), d - 14, 8 + i % 10);
            trace(pid, "t-later-" + i, userId(i), d - 13 + i % 14, 9 + i % 10);
        }

        hourly(pid, d);
        daily(pid, d);

        EndUserOverview o = data(controller.overview(owner(s), org(s), proj(s)));
        assertAll(
                () -> assertEquals(PageState.TOO_EARLY, o.pageState()),
                () -> assertEquals(PageStateReason.SHORT_HISTORY, o.pageStateReason()),
                () -> assertEquals(14.0, o.reasonNumbers().get("span_days")),
                () -> assertEquals(30.0, o.reasonNumbers().get("ids")),
                () -> assertNull(o.stages()),
                () -> assertNull(o.counted()));
    }

    /**
     * Z3. Witness refs on traces of 5 user IDs (2 on one of them) and on one trace with no user ID; member refs on 20
     * other user IDs. The bug: member refs count as hits, a user ID with two refs counts twice, the ref with no user ID
     * counts as a user ID, or the case page and the Triage chip disagree.
     */
    @Test
    void z3_userIdsHit_countsWitnessUserIdsOnce() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-hit");
        String pid = s.project().id();
        int d = jobDay();
        String caseId = seedToolErrorCase(pid, d);

        hourly(pid, d);
        daily(pid, d);

        EndUserCaseBlock block = data(controller.caseBlock(owner(s), org(s), proj(s), CASE_REF));
        EndUserHitCount chip = data(controller.hitCounts(owner(s), org(s), proj(s))).cases().stream()
                .filter(c -> c.caseId().equals(caseId))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertEquals(caseId, block.caseId()),
                () -> assertEquals(CountKind.COVERED, block.countKind(), "the judge has run"),
                () -> assertEquals(5, block.usersHit()),
                () -> assertEquals(7, block.flaggedTraces()),
                () -> assertEquals(1, block.flaggedTracesNoUserId()),
                () -> assertEquals(d - 45, block.sinceDay()),
                () -> assertEquals(5, chip.usersHit()),
                () -> assertEquals(CASE_REF, chip.caseRef()));
    }

    /**
     * Z4. The case is resolved, which closes its finding, so retention may delete its traces. The bug: the count dies
     * with the traces, the readable ID outlives its last trace, or the horizon sweep leaves facts past the horizon.
     */
    @Test
    void z4_retention_countOutlivesTraces_textDoesNot_horizonClearsAll() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-retention");
        String pid = s.project().id();
        int d = jobDay();
        String caseId = seedToolErrorCase(pid, d);
        hourly(pid, d);
        daily(pid, d);
        int before = Objects.requireNonNull(
                data(controller.caseBlock(owner(s), org(s), proj(s), CASE_REF)).usersHit());

        cases.resolve(pid, caseId, null, s.user().email(), null);
        ttl(pid, 30);
        String cutoff = Instant.now().minus(Duration.ofDays(30)).toString();
        retention.nullAssessmentRequests(pid, cutoff, RETENTION_BATCH);
        retention.deleteSpanPayloads(pid, cutoff, RETENTION_BATCH);
        retention.deleteTraces(pid, cutoff, RETENTION_BATCH);
        retention.deleteOrphanedEvidence(pid, RETENTION_BATCH);
        int tracesLeft = rows(pid, "trace");
        hourly(pid, d);
        daily(pid, d);

        EndUserCaseBlock after = data(controller.caseBlock(owner(s), org(s), proj(s), CASE_REF));
        List<String> texts = jdbc.sql("SELECT COALESCE(id_text, '') FROM end_user WHERE project_id = :pid")
                .param("pid", pid)
                .query(String.class)
                .list();
        OptionalInt kept = horizon.days(pid);
        sweep.run(pid, d + kept.orElseThrow());

        Map<String, Integer> left = new TreeMap<>();
        SWEPT_TABLES.forEach(t -> left.put(t, rows(pid, t)));
        Map<String, Integer> none = new TreeMap<>();
        SWEPT_TABLES.forEach(t -> none.put(t, 0));
        assertAll(
                () -> assertEquals(5, before),
                () -> assertEquals("resolved", caseState(pid, caseId)),
                () -> assertEquals("closed", findingState(pid)),
                () -> assertEquals(0, tracesLeft, "the closed finding no longer pins its traces"),
                () -> assertEquals(5, after.usersHit(), "the hit rows outlive the traces"),
                () -> assertEquals(25, texts.size(), "the IDs stay, with their shape"),
                () -> assertTrue(texts.stream().allMatch(String::isEmpty), "no readable ID outlives its traces"),
                () -> assertEquals(OptionalInt.of(400), kept, "the larger of the 30-day TTL and 400 days"),
                () -> assertEquals(none, left, "every fact is older than the horizon"));
    }

    /** Z5. Erasing one of the 5 hit user IDs. The bug: an erased person is still counted on the case. */
    @Test
    void z5_erase_removesTheIdFromTheCount() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-erase");
        String pid = s.project().id();
        int d = jobDay();
        seedToolErrorCase(pid, d);
        hourly(pid, d);
        daily(pid, d);

        int deleted = data(controller.erase(owner(s), org(s), proj(s), new EndUserEraseRequest(userId(1))))
                .deletedRows();

        EndUserCaseBlock block = data(controller.caseBlock(owner(s), org(s), proj(s), CASE_REF));
        assertAll(
                () -> assertTrue(deleted > 0, "the ID had rows to erase"),
                () -> assertEquals(4, block.usersHit()),
                () -> assertEquals(
                        4,
                        data(controller.hitCounts(owner(s), org(s), proj(s)))
                                .cases()
                                .getFirst()
                                .usersHit()));
    }

    /** Z6. Deleting the project after both jobs ran. The bug: an end-user table keeps rows of a deleted project. */
    @Test
    void z6_projectDelete_leavesNoEndUserRows() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-e2e-purge");
        String pid = s.project().id();
        int d = jobDay();
        seedToolErrorCase(pid, d);
        hourly(pid, d);
        daily(pid, d);
        Map<String, Integer> before = endUserRows(pid);

        tenants.deleteProjectAsync(pid, Instant.now().toString());
        purge.tick();

        Map<String, Integer> after = endUserRows(pid);
        Map<String, Integer> none = new TreeMap<>();
        after.keySet().forEach(t -> none.put(t, 0));
        assertAll(
                () -> assertEquals(25, before.get("end_user"), "the jobs wrote the facts"),
                () -> assertTrue(before.get("end_user_hit") > 0),
                () -> assertTrue(before.get("end_user_snapshot") > 0),
                () -> assertEquals(none, after));
    }

    /**
     * Case C-1 on one tool-error finding. User IDs 1 to 25 each have a trace 45 to 42 days before the job's day and one
     * 41 days before it. Witness refs: both traces of ID 1, the first trace of IDs 2 to 5, and one trace with no user
     * ID. Member refs: the second trace of IDs 6 to 25. Every trace is under the 90-day default TTL and past 30 days.
     *
     * @return the case id
     */
    private String seedToolErrorCase(String pid, int d) {
        int first = d - 45;
        for (int i = 1; i <= 25; i++) {
            trace(pid, "t-a-" + i, userId(i), first + i % 4, 8 + i % 10);
            trace(pid, "t-b-" + i, userId(i), d - 41, 9 + i % 10);
        }
        trace(pid, "t-anon", null, first + 2, 11);
        String caseId = "case-" + pid;
        String findingId = "finding-" + pid;
        String at = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, metric,
                                       state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at)
                VALUES (:id, :pid, :seq, 'tool_error', 'classifier', 'cls-tool', 'search tool', 'rate', 'open',
                        'search tool errors', 'basis', 0.5, :at, :at, :at, :at)
                """)
                .param("id", caseId)
                .param("pid", pid)
                .param("seq", CASE_SEQ)
                .param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, status,
                                     onset_at, last_seen_at, payload, case_id, created_at, updated_at)
                VALUES (:id, :pid, 'tool_error', :id, 'classifier', 'cls-tool', 'open', :at, :at, '{}'::jsonb, :case,
                        :at, :at)
                """)
                .param("id", findingId)
                .param("pid", pid)
                .param("case", caseId)
                .param("at", at)
                .update();
        evidence(pid, findingId, "t-a-1", "witness");
        evidence(pid, findingId, "t-b-1", "witness");
        for (int i = 2; i <= 5; i++) evidence(pid, findingId, "t-a-" + i, "witness");
        evidence(pid, findingId, "t-anon", "witness");
        for (int i = 6; i <= 25; i++) evidence(pid, findingId, "t-b-" + i, "member");
        return caseId;
    }

    /** The hourly job. */
    private void hourly(String pid, int day) {
        hits.run(pid, day);
    }

    /** The daily job's stages in the worker's order. */
    private void daily(String pid, int day) {
        for (EndUsersStage stage : List.of(fold, reconcile, learn, judge, sweep)) stage.run(pid, day);
    }

    /** The last closed UTC day, which the daily job judges. */
    private static int jobDay() {
        return EndUserKeys.dayIndex(Instant.now()) - 1;
    }

    private static String userId(int n) {
        return String.format("user-%02d", n);
    }

    /**
     * A settled trace on one route. Its minute and second are on no 5-minute or whole-minute mark, so the route is not
     * read as a schedule.
     */
    private void trace(String pid, String id, @Nullable String userId, int day, int hour) {
        Timestamp at = Timestamp.from(Instant.ofEpochSecond(day * 86_400L)
                .plus(Duration.ofHours(hour))
                .plus(Duration.ofMinutes(7))
                .plusSeconds(13));
        jdbc.sql("""
                INSERT INTO trace (project_id, id, user_id, name, started_at, event_ts, is_settled, created_at)
                VALUES (:pid, :id, :user, :name, :at, :at, true, :at)
                """)
                .param("pid", pid)
                .param("id", id)
                .param("user", userId)
                .param("name", TRACE_NAME)
                .param("at", at)
                .update();
    }

    private void evidence(String pid, String findingId, String traceId, String role) {
        jdbc.sql("""
                INSERT INTO finding_evidence (id, project_id, finding_id, trace_id, role, created_at)
                VALUES (:id, :pid, :fid, :tid, :role, :at)
                """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("fid", findingId)
                .param("tid", traceId)
                .param("role", role)
                .param("at", Instant.now().toString())
                .update();
    }

    private void ttl(String pid, int days) {
        jdbc.sql("""
                INSERT INTO retention_policy (id, project_id, data_class, ttl_days, created_at)
                VALUES (:id, :pid, 'traces', :days, :at)
                """)
                .param("id", "rp-" + pid)
                .param("pid", pid)
                .param("days", days)
                .param("at", Instant.now().toString())
                .update();
    }

    private String caseState(String pid, String caseId) {
        return jdbc.sql("SELECT state FROM eval_case WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", caseId)
                .query(String.class)
                .single();
    }

    private String findingState(String pid) {
        return jdbc.sql("SELECT status FROM finding WHERE project_id = :pid")
                .param("pid", pid)
                .query(String.class)
                .single();
    }

    /** Every table named {@code end_user} or {@code end_user_*}, so a table added later is counted too. */
    private Map<String, Integer> endUserRows(String pid) {
        Map<String, Integer> counts = new TreeMap<>();
        jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                        + " AND (tablename = 'end_user' OR tablename LIKE 'end\\_user\\_%')")
                .query(String.class)
                .list()
                .forEach(t -> counts.put(t, rows(pid, t)));
        return counts;
    }

    private int rows(String pid, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }

    /** Shares to 6 places: {@code 1 - 90/100.0} is not the double {@code 0.1}. */
    private static Map<String, Double> rounded(Map<String, Double> numbers) {
        Map<String, Double> out = new TreeMap<>();
        numbers.forEach((k, v) -> out.put(k, Math.round(v * 1e6) / 1e6));
        return out;
    }

    private static TenantContext owner(Setup s) {
        return new TenantContext(s.user().id(), s.user().email(), null, null, null, null);
    }

    private static String org(Setup s) {
        return s.org().slug();
    }

    private static String proj(Setup s) {
        return s.project().slug();
    }

    private static <T> T data(ApiResponse<T> response) {
        return Objects.requireNonNull(response.data());
    }
}

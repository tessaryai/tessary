// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.endusers.facts.RouteClassRepository;
import ai.tessary.endusers.facts.ShapeSignature;
import ai.tessary.endusers.hits.EndUserHitRepository;
import ai.tessary.endusers.hits.HitRules;
import ai.tessary.endusers.hits.IssueLineages;
import ai.tessary.endusers.job.FactsLoadRepository.Loaded;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.read.IssueDiagnostics;
import ai.tessary.endusers.read.SnapshotReadRepository;
import ai.tessary.endusers.read.SnapshotReadRepository.SnapshotRow;
import ai.tessary.endusers.read.SnapshotWriteRepository;
import ai.tessary.endusers.read.SnapshotWriteRepository.Run;
import ai.tessary.endusers.read.SnapshotWriteRepository.SilenceEvent;
import ai.tessary.endusers.read.SnapshotWriteRepository.Snapshot;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The judge stage of the daily job against real Postgres: the facts it loads, the size budget, the path for a project
 * with no user ID, and the one transaction its writes share. A fact read from outside the window, a snapshot written
 * half, or a failed run that leaves a new snapshot or memory behind shows numbers no whole judge run gave.
 */
@SpringBootTest
record JudgeStageTest(
        TenantService tenants,
        JdbcClient jdbc,
        DataSource dataSource,
        JudgeStage judge,
        SnapshotWriteRepository writer,
        SnapshotReadRepository snapshots,
        EndUserProjectRepository projects,
        RouteClassRepository routes,
        EndUserHitRepository hits,
        IssueLineages lineages,
        HitRules rules,
        EndUserHistoryHorizon horizon,
        EndUsersProperties props,
        PlatformTransactionManager transactions) {

    private static final int D = 20_600;
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] SALT =
            IntStream.range(0, 32).collect(() -> new byte[32], (b, i) -> b[i] = (byte) (i * 7 + 3), (a, b) -> {});
    private static final byte[] NO_USER_KEY = new byte[EndUserKeys.KEY_BYTES];
    private static final String SESSIONS =
            "[{\"refs\": 2, \"routes\": {\"1\": 3}}, {\"refs\": 1, \"routes\": {\"2\": 1}}]";

    private static byte[] key(String userId) {
        return EndUserKeys.userKey(SALT, userId);
    }

    @Test
    void window_is180Days_andT0IsTheStoredHistoryStart() {
        Seed s = seed("eu-judge-window", D - 300);
        byte[] ana = key("ana");
        byte[] ben = key("ben");
        byte[] cal = key("acme:cal");
        s.route(1, "A");
        s.route(2, "B");
        s.user(ana, "ana", D - 250, D, true);
        s.user(ben, "ben", D - 180, D - 180, true);
        s.user(cal, "acme:cal", D - 1, D - 1, false);
        s.day(ana, D - 180, 1);
        s.day(ana, D - 179, 1);
        s.day(ana, D, 1);
        s.day(ben, D - 180, 1);
        s.day(cal, D - 1, 2);
        s.nullDay(D - 180, 1, 4, 1);
        s.nullDay(D - 179, 1, 3, 1);
        s.edge(ana, ben, D - 179);
        s.edge(ana, cal, D - 1);
        s.foldDay(D - 180, false, true);
        s.foldDay(D - 20, true, false);
        s.foldDay(D - 10, false, true);
        s.version(1, "pv-1", D - 250);
        s.caseRow("c1", 1);
        s.finding("f1", "c1");
        s.hit("f1", "witness", ana, D - 180, 1, 1, null);
        s.hit("f1", "witness", ana, D - 179, 1, 2, null);
        s.hit("f1", "member", ana, D - 179, 1, 5, null);
        s.hit("f1", "witness", NO_USER_KEY, D, 1, 1, null);
        s.hit("f1", "witness", cal, D - 1, 0, 3, SESSIONS);
        projects.rememberPageState(s.pid(), PageState.OK);
        List<Loaded> seen = new ArrayList<>();
        FactsLoadRepository recording = new FactsLoadRepository(dataSource, routes, hits, lineages, rules, horizon) {
            @Override
            public Loaded load(String projectId, int asOfDay, int fromDay, int t0, List<PageState> memory) {
                Loaded l = super.load(projectId, asOfDay, fromDay, t0, memory);
                seen.add(l);
                return l;
            }
        };

        new JudgeStage(recording, writer, projects, routes, props, transactions).run(s.pid(), D);

        Loaded loaded = seen.getFirst();

        Facts f = loaded.facts();
        boolean anaFirst = Arrays.compareUnsigned(ana, cal) < 0;
        int a = anaFirst ? 0 : 1;
        int c = 1 - a;
        String lineage = lineages.lineages(s.pid()).getFirst().lineageKey();
        assertAll(
                () -> assertEquals(D, f.asOfDay()),
                () -> assertEquals(D - 300, f.t0(), "t0 is the stored start, not the window's first day"),
                () -> assertEquals(400, f.horizonDays()),
                () -> assertEquals(2, loaded.keys().size(), "an ID with no day row in the window is not loaded"),
                () -> assertArrayEquals(ana, loaded.keys().get(a)),
                () -> assertArrayEquals(cal, loaded.keys().get(c)),
                () -> assertEquals(
                        anaFirst
                                ? List.of(
                                        new IdInfo("", 3, null, D - 250, D, 1, true),
                                        new IdInfo("acme", 8, 0, D - 1, D - 1, 1, false))
                                : List.of(
                                        new IdInfo("acme", 8, 0, D - 1, D - 1, 1, false),
                                        new IdInfo("", 3, null, D - 250, D, 1, true)),
                        f.ids()),
                () -> assertEquals(
                        anaFirst
                                ? List.of(a + "|" + (D - 179) + "|1", a + "|" + D + "|1", c + "|" + (D - 1) + "|2")
                                : List.of(c + "|" + (D - 1) + "|2", a + "|" + (D - 179) + "|1", a + "|" + D + "|1"),
                        IntStream.range(0, f.idDays().id().length)
                                .mapToObj(i -> f.idDays().id()[i] + "|"
                                        + f.idDays().day()[i] + "|" + f.idDays().routeNo()[i])
                                .toList()),
                () -> assertEquals(
                        List.of(new NullDay(D - 179, 1, 1, 3, 1)), f.nullDays(), "a null-day row before the window"),
                () -> assertEquals(
                        List.of(new Facts.AliasEdge(a, c, ai.tessary.endusers.model.AliasVia.SESSION, D - 1)),
                        f.aliasEdges(),
                        "an edge to an ID outside the window"),
                () -> assertEquals(List.of(new Facts.Version(1, "pv-1", D - 250)), f.versions()),
                () -> assertEquals(
                        List.of(new IssueInput(lineage, s.id("c1"), List.of(s.id("f1")), true, false)), f.issues()),
                () -> assertEquals(
                        Set.of(
                                new HitRow(0, a, D - 179, 1, Map.of(), 2),
                                new HitRow(0, null, D, 1, Map.of(), 1),
                                new HitRow(0, c, D - 1, null, Map.of(1, 3), 2),
                                new HitRow(0, c, D - 1, null, Map.of(2, 1), 1)),
                        new HashSet<>(f.hits()),
                        "witness rows of the window, one row per session of a ref to a session only"),
                () -> assertEquals(
                        Set.of(1, 2), f.storedRouteClasses().byRoute().keySet()),
                () -> assertEquals(List.of(D - 10), f.outageDays()),
                () -> assertEquals(List.of(D - 20), f.gapDays()),
                () -> assertEquals(List.of(PageState.OK), f.pageStateMemory()));
    }

    @Test
    void run_writesTheJudgedSnapshot_withEveryIdAndIssueOfTheRun() {
        Seed s = seed("eu-judge-run", D - 60);
        s.route(1, "A");
        List<byte[]> keys = new ArrayList<>();
        for (String id : List.of("ana", "ben", "cal")) {
            byte[] k = key(id);
            keys.add(k);
            s.user(k, id, D - 40, D, true);
            for (int d = D - 40; d <= D; d += id.equals("cal") ? 1 : 2) s.day(k, d, 1);
        }
        byte[] dan = key("dan");
        s.user(dan, "dan", D - 50, D - 42, true);
        for (int d = D - 50; d <= D - 42; d += 2) s.day(dan, d, 1);
        s.edge(dan, keys.get(2), D - 42);
        for (int d = D - 50; d <= D; d++) s.nullDay(d, 1, 7, 1);
        s.caseRow("c1", 4);
        s.finding("f1", "c1");
        s.hit("f1", "witness", keys.get(1), D - 10, 1, 1, null);

        long rows = judge.run(s.pid(), D);

        SnapshotRow snapshot = snapshots.latest(s.pid()).orElseThrow();
        assertAll(
                () -> assertEquals(1 + 1 + 1 + 4, rows),
                () -> assertEquals(D, snapshot.day()),
                () -> assertEquals(PageState.TOO_EARLY, snapshot.pageState(), "3 IDs are under the 20 of spec 3.5"),
                () -> assertEquals(PageStateReason.FEW_IDS, snapshot.reason()),
                () -> assertEquals(
                        BannerCode.PAGE_REFUSAL, snapshot.banners().getFirst().code()),
                () -> assertEquals(
                        List.of("unknown|", "unknown|", "unknown|", "unknown|"),
                        s.strings("SELECT state || '|' || COALESCE(stage, '') FROM end_user WHERE project_id = :pid"),
                        "a refused page puts no ID in a stage, and an alias member takes its representative's state"),
                () -> assertEquals(
                        List.of("cal|service", "dan|service"),
                        s.strings("SELECT id_text || '|' || COALESCE(set_aside_reason, '-') FROM end_user"
                                + " WHERE project_id = :pid AND id_text IN ('cal', 'dan') ORDER BY id_text"),
                        "cal is on every day of its life with the same traces (spec 3.1 service); a hit on its alias"
                                + " member dan counts as covered only when cal is"),
                () -> assertEquals(
                        List.of(D + "|" + s.id("c1") + "|page_refused|1|f"),
                        s.strings("""
                                SELECT day || '|' || case_id || '|' || verdict || '|' || user_ids_hit || '|'
                                       || CASE WHEN shown THEN 't' ELSE 'f' END
                                  FROM end_user_issue WHERE project_id = :pid
                                """),
                        "the issue is of the snapshot day, with its hit user ID"),
                () -> assertEquals(List.of(HEX.formatHex(keys.get(1)) + "|" + (D - 10) + "|f"), s.strings("""
                                SELECT encode(user_key, 'hex') || '|' || first_hit_day || '|'
                                       || CASE WHEN in_estimate THEN 't' ELSE 'f' END
                                  FROM end_user_issue_hit WHERE project_id = :pid
                                """)),
                () -> assertEquals(
                        List.of(PageState.TOO_EARLY), projects.state(s.pid()).pageStateMemory()));
    }

    @Test
    void name_isStable_becauseRetriesResumeByName() {
        assertEquals("judge", judge.name());
    }

    @Test
    void dayRowWithNoStoredId_failsTheRun_ratherThanJudgeAnIdWithNoShapeOrFirstDay() {
        Seed s = seed("eu-judge-orphan", D - 60);
        s.route(1, "A");
        s.day(key("ana"), D, 1);

        assertThrows(IllegalStateException.class, () -> judge.run(s.pid(), D));

        assertEquals(Optional.empty(), snapshots.latest(s.pid()));
    }

    @Test
    void noHistory_writesNoSnapshot_soThePageStaysCollecting() {
        Seed s = new Seed(
                jdbc,
                TenantFixture.bootstrap(tenants, "eu-judge-no-history")
                        .project()
                        .id());

        long rows = judge.run(s.pid(), D);

        assertAll(
                () -> assertEquals(0, rows),
                () -> assertEquals(Optional.empty(), snapshots.latest(s.pid())),
                () -> assertEquals(List.of(), projects.state(s.pid()).pageStateMemory()));
    }

    @Test
    void overBudget_writesNotComputed_andKeepsFacts() {
        Seed s = seed("eu-judge-budget", D - 60);
        s.route(1, "A");
        byte[] ana = key("ana");
        s.user(ana, "ana", D - 5, D, true);
        s.day(ana, D, 1);
        s.judged(ana, "active", "active");
        s.snapshot(D - 1, "ok");
        projects.rememberPageState(s.pid(), PageState.OK);
        JudgeStage stage = stage(5_000_001);

        stage.run(s.pid(), D);

        assertAll(
                () -> assertEquals(
                        List.of((D - 1) + "|ok", D + "|not_computed"),
                        s.strings("SELECT day || '|' || page_state FROM end_user_snapshot WHERE project_id = :pid"
                                + " ORDER BY day")),
                () -> assertEquals(
                        List.of("1"), s.strings("SELECT count(*) FROM end_user_day WHERE project_id = :pid")),
                () -> assertEquals(
                        List.of("-|-"),
                        s.strings("SELECT COALESCE(state, '-') || '|' || COALESCE(stage, '-') FROM end_user"
                                + " WHERE project_id = :pid"),
                        "no ID keeps a state the page does not compute"),
                () -> assertEquals(
                        List.of(PageState.OK),
                        projects.state(s.pid()).pageStateMemory(),
                        "a run that computed no state leaves no memory"));
    }

    @Test
    void noUserIdProject_getsNoUserIdSnapshot_cheaply() {
        Seed s = seed("eu-judge-no-id", D - 30);
        s.route(1, "A");
        s.nullDay(D - 1, 1, 5, 5);
        s.nullDay(D, 1, 5, 5);
        JudgeStage stage = stage(0);

        stage.run(s.pid(), D);

        SnapshotRow snapshot = snapshots.latest(s.pid()).orElseThrow();
        assertAll(
                () -> assertEquals(PageState.NO_USER_ID, snapshot.pageState()),
                () -> assertEquals(PageStateReason.LOW_ID_SHARE, snapshot.reason()),
                () -> assertEquals(Map.of("ident_share", 0.0), snapshot.reasonNumbers()),
                () -> assertEquals(
                        List.of(new Banner(BannerCode.PAGE_REFUSAL, Map.of("ident_share", 0.0))), snapshot.banners()),
                () -> assertEquals(List.of(), snapshot.limits()),
                () -> assertEquals(null, snapshot.stages()),
                () -> assertEquals(
                        List.of(PageState.NO_USER_ID), projects.state(s.pid()).pageStateMemory()));
    }

    @Test
    void judgeFailure_keepsLastSnapshot() {
        Seed s = seed("eu-judge-fail", D - 60);
        s.route(1, "A");
        jdbc.sql("INSERT INTO end_user_route (project_id, route_no, trace_name, call_site_id) VALUES (:pid, 2, 'x',"
                        + " '__unattributed__')")
                .param("pid", s.pid())
                .update();
        byte[] ana = key("ana");
        s.user(ana, "ana", D - 5, D, true);
        s.day(ana, D, 2);
        s.judged(ana, "active", "active");
        s.snapshot(D - 1, "too_early");
        projects.rememberPageState(s.pid(), PageState.TOO_EARLY);

        assertThrows(IllegalStateException.class, () -> judge.run(s.pid(), D), "a day row on a route with no class");

        assertAll(
                () -> assertEquals(
                        D - 1, snapshots.latest(s.pid()).orElseThrow().day()),
                () -> assertEquals(
                        List.of("active|active"),
                        s.strings("SELECT state || '|' || stage FROM end_user WHERE project_id = :pid")),
                () -> assertEquals(
                        List.of(PageState.TOO_EARLY), projects.state(s.pid()).pageStateMemory()));
    }

    @Test
    void writes_areOneTransaction() {
        Seed s = seed("eu-judge-tx", D - 60);
        byte[] ana = key("ana");
        s.user(ana, "ana", D - 5, D, true);
        s.judged(ana, "active", "active");
        s.snapshot(D - 1, "ok");
        projects.rememberPageState(s.pid(), PageState.OK);
        SilenceEvent event = new SilenceEvent("app", D - 9, null, SilenceState.OPEN, 3, 0.4, 0.1, 0.5);
        Run run = new Run(
                new Snapshot(
                        D,
                        "v3",
                        PageState.TOO_EARLY,
                        PageStateReason.FEW_IDS,
                        Map.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                List.of(new SnapshotWriteRepository.Issue(
                        "lin-1",
                        null,
                        List.of(),
                        1,
                        0,
                        null,
                        null,
                        null,
                        null,
                        ai.tessary.endusers.model.Verdict.PAGE_REFUSED,
                        false,
                        null,
                        null,
                        new IssueDiagnostics(null, null, null, null, null, null, null, null, null, null, null, null))),
                List.of(),
                List.of(),
                List.of(event, event));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> judge.write(s.pid(), run, PageState.TOO_EARLY),
                "the last statement breaks the silence event key");

        assertAll(
                () -> assertEquals(
                        D - 1, snapshots.latest(s.pid()).orElseThrow().day()),
                () -> assertEquals(
                        List.of("0"), s.strings("SELECT count(*) FROM end_user_issue WHERE project_id = :pid")),
                () -> assertEquals(
                        List.of("active|active"),
                        s.strings("SELECT state || '|' || stage FROM end_user WHERE project_id = :pid")),
                () -> assertEquals(
                        List.of(PageState.OK), projects.state(s.pid()).pageStateMemory()));
    }

    /** Each install key moves its own guard and no other: the two records list them in one order, so a swap compiles. */
    @ParameterizedTest
    @EnumSource(GuardKey.class)
    void guards_comeFromInstallKeys(GuardKey off) {
        Guards g = JudgeStage.guards(off.properties);

        for (GuardKey k : GuardKey.values()) {
            assertEquals(k != off, k.read.apply(g), k.name() + " with " + off.name() + " off");
        }
    }

    enum GuardKey {
        NOT_JUDGED_WITHOUT_PAST_CHECK(
                new EndUsersProperties.Guards(false, true, true, true, true, true, true),
                Guards::notJudgedWithoutPastCheck),
        CANNOT_TELL_YET(
                new EndUsersProperties.Guards(true, false, true, true, true, true, true), Guards::cannotTellYet),
        HISTORY_TOO_SHORT(
                new EndUsersProperties.Guards(true, true, false, true, true, true, true), Guards::historyTooShort),
        HITS_NOT_COMPLETE(
                new EndUsersProperties.Guards(true, true, true, false, true, true, true), Guards::hitsNotComplete),
        MIXED_ID_FORMATS(
                new EndUsersProperties.Guards(true, true, true, true, false, true, true), Guards::mixedIdFormats),
        FINDINGS_FIRE_TOGETHER(
                new EndUsersProperties.Guards(true, true, true, true, true, false, true), Guards::findingsFireTogether),
        OUTAGE_BANNER(new EndUsersProperties.Guards(true, true, true, true, true, true, false), Guards::outageBanner);

        final EndUsersProperties.Guards properties;
        final Function<Guards, Boolean> read;

        GuardKey(EndUsersProperties.Guards properties, Function<Guards, Boolean> read) {
            this.properties = properties;
            this.read = read;
        }
    }

    /** The stage with a loader that counts {@code idDays} user-ID days and fails a full fact load. */
    private JudgeStage stage(long idDays) {
        FactsLoadRepository counting = new FactsLoadRepository(dataSource, routes, hits, lineages, rules, horizon) {
            @Override
            public long idDays(String projectId, int fromDay, int toDay) {
                return idDays;
            }

            @Override
            public Loaded load(String projectId, int asOfDay, int fromDay, int t0, List<PageState> memory) {
                throw new AssertionError("the full fact load must not run");
            }
        };
        return new JudgeStage(counting, writer, projects, routes, props, transactions);
    }

    private Seed seed(String name, int historyStartDay) {
        Seed s = new Seed(jdbc, TenantFixture.bootstrap(tenants, name).project().id());
        jdbc.sql("INSERT INTO end_user_project (project_id, salt, history_start_day) VALUES (:pid, :salt, :day)")
                .param("pid", s.pid())
                .param("salt", SALT)
                .param("day", historyStartDay)
                .update();
        return s;
    }

    /** Fact rows as the fold, the learn step, the hit sweep and an earlier judge run write them. */
    private record Seed(JdbcClient jdbc, String pid) {

        String id(String name) {
            return name + "-" + pid;
        }

        void route(int routeNo, String routeClass) {
            jdbc.sql("""
                    INSERT INTO end_user_route (project_id, route_no, trace_name, call_site_id, route_class, statistics)
                    VALUES (:pid, :no, :name, '__unattributed__', :class, CAST(:stats AS jsonb))
                    """)
                    .param("pid", pid)
                    .param("no", routeNo)
                    .param("name", "route-" + routeNo)
                    .param("class", routeClass)
                    .param("stats", """
                            {"n": 40, "null_share": 0.0, "inp": 1.0, "par": 0.0, "sec0": 0.0, "min5": 0.0,
                             "follow": 0.5, "dq10": 4.0, "dq90": 90.0, "conc": null, "sched_r": 0.0, "co_anchor": null}
                            """)
                    .update();
        }

        void user(byte[] key, String idText, int firstDay, int lastDay, boolean tokenLink) {
            String cluster = ShapeSignature.clusterOf(idText);
            jdbc.sql("""
                    INSERT INTO end_user (project_id, user_key, id_text, shape_signature, cluster_hash, first_seen_day,
                                          last_seen_day, session_token_link)
                    VALUES (:pid, :key, :text, :shape, :cluster, :first, :last, :link)
                    """)
                    .param("pid", pid)
                    .param("key", key)
                    .param("text", idText)
                    .param("shape", ShapeSignature.of(idText).wire())
                    .param("cluster", cluster == null ? null : ShapeSignature.clusterHash(SALT, cluster))
                    .param("first", firstDay)
                    .param("last", lastDay)
                    .param("link", tokenLink)
                    .update();
        }

        void judged(byte[] key, String state, String stage) {
            jdbc.sql("UPDATE end_user SET state = :state, stage = :stage WHERE project_id = :pid AND user_key = :key")
                    .param("state", state)
                    .param("stage", stage)
                    .param("pid", pid)
                    .param("key", key)
                    .update();
        }

        void day(byte[] key, int day, int routeNo) {
            jdbc.sql("""
                    INSERT INTO end_user_day (project_id, user_key, day, route_no, version_no, traces, session_hashes,
                                              hour_mask, slot_mask, job_keys, traces_with_input, traces_with_parent)
                    VALUES (:pid, :key, :day, :route, 1, 2, '{11}', 4096, CAST(:slots AS bit(96)), '{}', 2, 0)
                    """)
                    .param("pid", pid)
                    .param("key", key)
                    .param("day", day)
                    .param("route", routeNo)
                    .param("slots", "0".repeat(47) + "1" + "0".repeat(48))
                    .update();
        }

        void nullDay(int day, int routeNo, int traces, int noUserId) {
            jdbc.sql("""
                    INSERT INTO end_user_day_null (project_id, day, route_no, version_no, traces, traces_no_user_id)
                    VALUES (:pid, :day, :route, 1, :traces, :noId)
                    """)
                    .param("pid", pid)
                    .param("day", day)
                    .param("route", routeNo)
                    .param("traces", traces)
                    .param("noId", noUserId)
                    .update();
        }

        void edge(byte[] from, byte[] to, int day) {
            jdbc.sql("""
                    INSERT INTO end_user_alias_edge (project_id, from_key, to_key, via, day)
                    VALUES (:pid, :from, :to, 'session', :day)
                    """)
                    .param("pid", pid)
                    .param("from", from)
                    .param("to", to)
                    .param("day", day)
                    .update();
        }

        void foldDay(int day, boolean gap, boolean outage) {
            jdbc.sql("""
                    INSERT INTO end_user_fold_day (project_id, day, folded_at, traces, gap, outage)
                    VALUES (:pid, :day, now(), 10, :gap, :outage)
                    """)
                    .param("pid", pid)
                    .param("day", day)
                    .param("gap", gap)
                    .param("outage", outage)
                    .update();
        }

        void version(int versionNo, String projectVersionId, int firstTraceDay) {
            jdbc.sql("""
                    INSERT INTO end_user_version (project_id, version_no, project_version_id, first_trace_day)
                    VALUES (:pid, :no, :pv, :day)
                    """)
                    .param("pid", pid)
                    .param("no", versionNo)
                    .param("pv", projectVersionId)
                    .param("day", firstTraceDay)
                    .update();
        }

        void caseRow(String name, long seq) {
            jdbc.sql("""
                    INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,
                                           metric, state, title, basis, severity, onset_at, opened_at, last_seen_at,
                                           updated_at)
                    VALUES (:id, :pid, :seq, 'tool_error', 'tool', :subject, :subject, 'rate', 'open', 'title',
                            'basis', 0.5, '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z',
                            '2026-05-01T00:00:00Z')
                    """)
                    .param("id", id(name))
                    .param("pid", pid)
                    .param("seq", seq)
                    .param("subject", "tool-" + name)
                    .update();
        }

        void finding(String name, String caseName) {
            jdbc.sql("""
                    INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, status,
                                         onset_at, last_seen_at, payload, case_id, created_at, updated_at)
                    VALUES (:id, :pid, 'tool_error', :id, 'tool', :subject, 'open', '2026-05-01T00:00:00Z',
                            '2026-05-01T00:00:00Z', '{}'::jsonb, :case, '2026-05-01T00:00:00Z',
                            '2026-05-01T00:00:00Z')
                    """)
                    .param("id", id(name))
                    .param("pid", pid)
                    .param("subject", "tool-" + name)
                    .param("case", id(caseName))
                    .update();
        }

        void hit(
                String findingName,
                String role,
                byte[] key,
                int day,
                int routeNo,
                int refs,
                @Nullable String sessionRoutes) {
            jdbc.sql("""
                    INSERT INTO end_user_hit (project_id, finding_id, role, user_key, day, route_no, refs, source,
                                              session_routes)
                    VALUES (:pid, :fid, :role, :key, :day, :route, :refs, 'evidence', CAST(:sessions AS jsonb))
                    """)
                    .param("pid", pid)
                    .param("fid", id(findingName))
                    .param("role", role)
                    .param("key", key)
                    .param("day", day)
                    .param("route", routeNo)
                    .param("refs", refs)
                    .param("sessions", sessionRoutes)
                    .update();
        }

        void snapshot(int day, String pageState) {
            jdbc.sql("""
                    INSERT INTO end_user_snapshot (project_id, day, logic_version, page_state)
                    VALUES (:pid, :day, 'v3', :state)
                    """)
                    .param("pid", pid)
                    .param("day", day)
                    .param("state", pageState)
                    .update();
        }

        List<String> strings(String sql) {
            return jdbc.sql(sql).param("pid", pid).query(String.class).list();
        }
    }
}

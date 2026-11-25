// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.judge.model.StopOdds.CalibrationBlock;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.read.EndUserListRepository.IdRow;
import ai.tessary.endusers.read.IssueReadRepository.IssueRow;
import ai.tessary.endusers.read.IssueReadRepository.SilentIdRow;
import ai.tessary.endusers.read.SnapshotReadRepository.SnapshotRow;
import ai.tessary.endusers.read.SnapshotWriteRepository.Issue;
import ai.tessary.endusers.read.SnapshotWriteRepository.IssueHit;
import ai.tessary.endusers.read.SnapshotWriteRepository.Run;
import ai.tessary.endusers.read.SnapshotWriteRepository.SilenceEvent;
import ai.tessary.endusers.read.SnapshotWriteRepository.Snapshot;
import ai.tessary.endusers.read.SnapshotWriteRepository.UserState;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The judge run's write of the read model, read back through the read repositories. A field the writer drops, a jsonb
 * key the readers do not carry, an issue row on another day than its snapshot, or a state left from the last run shows
 * the page numbers no judge run gave.
 */
@SpringBootTest
record SnapshotWriteRepositoryTest(
        TenantService tenants,
        JdbcClient jdbc,
        SnapshotWriteRepository writer,
        SnapshotReadRepository snapshots,
        IssueReadRepository issues,
        EndUserListRepository ids) {

    private static final int D = 20_500;

    private static final byte[] ANA = ReadSeeds.key("ana");
    private static final byte[] BEN = ReadSeeds.key("ben");
    private static final byte[] CAL = ReadSeeds.key("cal");

    private ReadSeeds seed(String name) {
        ReadSeeds seed = new ReadSeeds(
                jdbc, TenantFixture.bootstrap(tenants, name).project().id());
        seed.project(D - 300);
        seed.caseRow("c1", 7, "tool_error", false);
        seed.foldedUser(ANA, "ana");
        seed.foldedUser(BEN, "ben");
        seed.foldedUser(CAL, "cal");
        return seed;
    }

    @Test
    void snapshot_roundTripsEveryField() {
        ReadSeeds seed = seed("eu-write-roundtrip");
        Snapshot snapshot = new Snapshot(
                D,
                "v3",
                PageState.PARTIAL_COVERAGE,
                PageStateReason.ID_LOSS,
                Map.of("covered_share", 0.93, "ident_share", 0.8),
                List.of(new Banner(BannerCode.ID_LOSS, Map.of("onset_day", (double) (D - 20)), List.of(2, 5))),
                List.of(new LimitLine(LimitCode.NO_ANCHOR, Map.of("days", 12.0), false, 40)),
                new TrustNumbers(0.9, null, true),
                new StageCounts(10, 2, 3, 1, 4, 4.5, 3.0, 6.0, 17),
                Map.of(SetAsideReason.ODD_SHAPE, 6),
                StopStageState.EXPECTED_ONLY,
                Map.of("expected", 4.5),
                List.of(new CalibrationBlock(Stratum.E, true, 0.1, 0.4, 0.25, 41)),
                21,
                28);
        IssueDiagnostics diagnostics = new IssueDiagnostics(
                9.0, 4.0, 30.0, 21, 2, 1, 14, 3.5, D - 60, 40, 3, List.of(new AccountLine(1, 12, 5, 2, D - 9)));
        List<CurvePoint> curve = List.of(new CurvePoint(1, 0.9, 0.95, 0.85, 0.95, 40, 300));
        Issue issue = new Issue(
                "lin-1",
                seed.id("c1"),
                List.of(seed.id("f1"), seed.id("f2")),
                1,
                3,
                38,
                6.0,
                2.0,
                11.0,
                Verdict.EVIDENCE_OF_LOSS,
                true,
                "lin-1",
                curve,
                diagnostics);
        Run run = new Run(
                snapshot,
                List.of(issue),
                List.of(new IssueHit("lin-1", ANA, D - 30, true), new IssueHit("lin-1", BEN, D - 20, false)),
                List.of(
                        new UserState(
                                ANA,
                                UserIdState.STOPPED,
                                null,
                                Grade.PROBABLY_STOPPED,
                                0.7,
                                0.65,
                                40,
                                null,
                                Stage.STOPPED),
                        new UserState(
                                BEN, UserIdState.ACTIVE, Substate.OVERDUE, null, 0.4, 0.38, 12, null, Stage.SILENT),
                        new UserState(
                                CAL, UserIdState.ACTIVE, null, null, 0.1, 0.12, 1, SetAsideReason.ODD_SHAPE, null)),
                List.of(new SilenceEvent("shape:usr/9", D - 25, null, SilenceState.OPEN, 14, 0.4, 0.1, 0.6)));

        long rows = writer.write(seed.pid(), run);

        SnapshotRow read = snapshots.latest(seed.pid()).orElseThrow();
        List<IssueRow> listed = issues.issues(seed.pid(), D);
        assertAll(
                () -> assertEquals(1 + 1 + 2 + 3 + 1, rows),
                () -> assertEquals(
                        new SnapshotRow(
                                D,
                                PageState.PARTIAL_COVERAGE,
                                PageStateReason.ID_LOSS,
                                snapshot.reasonNumbers(),
                                snapshot.banners(),
                                snapshot.limits(),
                                snapshot.trust(),
                                snapshot.stages(),
                                snapshot.setAside(),
                                StopStageState.EXPECTED_ONLY,
                                snapshot.stopStageNumbers()),
                        read),
                () -> assertEquals(
                        List.of("v3|21|28|[{\"n\": 41, \"p_low\": 0.1, \"value\": 0.25, \"beyond\": true, \"p_high\":"
                                + " 0.4, \"stratum\": \"E\"}]"),
                        strings(seed, """
                                SELECT logic_version || '|' || h || '|' || f || '|' || calibration::text
                                  FROM end_user_snapshot WHERE project_id = :pid
                                """)),
                () -> assertEquals(
                        List.of(new IssueRow(
                                "lin-1",
                                D,
                                seed.id("c1"),
                                7,
                                "title c1",
                                "tool_error",
                                "label c1",
                                1,
                                3,
                                38,
                                6.0,
                                2.0,
                                11.0,
                                Verdict.EVIDENCE_OF_LOSS,
                                true,
                                "lin-1",
                                curve,
                                diagnostics)),
                        listed),
                () -> assertEquals(List.of(seed.id("f1") + "," + seed.id("f2")), strings(seed, """
                                SELECT array_to_string(finding_ids, ',') FROM end_user_issue WHERE project_id = :pid
                                """)),
                () -> assertEquals(
                        List.of(
                                new SilentIdRow(
                                        "ana",
                                        D - 30,
                                        40,
                                        Stage.STOPPED,
                                        Grade.PROBABLY_STOPPED,
                                        40,
                                        ReadSeeds.md5(ANA)),
                                new SilentIdRow("ben", D - 20, 12, Stage.SILENT, null, 12, ReadSeeds.md5(BEN))),
                        issues.silentIds(seed.pid(), "lin-1", null, 10)),
                () -> assertEquals(List.of(ReadSeeds.md5(ANA) + "|t", ReadSeeds.md5(BEN) + "|f"), strings(seed, """
                                SELECT md5(user_key) || '|' || CASE WHEN in_estimate THEN 't' ELSE 'f' END
                                  FROM end_user_issue_hit WHERE project_id = :pid ORDER BY first_hit_day
                                """)),
                () -> assertEquals(
                        List.of(new IdRow(
                                "ana",
                                UserIdState.STOPPED,
                                null,
                                Grade.PROBABLY_STOPPED,
                                40,
                                0.7,
                                List.of("C-7"),
                                40,
                                ReadSeeds.md5(ANA))),
                        ids.page(seed.pid(), Stage.STOPPED, null, 10)),
                () -> assertEquals(
                        List.of(new IdRow(
                                "ben",
                                UserIdState.ACTIVE,
                                Substate.OVERDUE,
                                null,
                                12,
                                0.4,
                                List.of("C-7"),
                                12,
                                ReadSeeds.md5(BEN))),
                        ids.page(seed.pid(), Stage.SILENT, null, 10),
                        "an issue that claims a loss names every user ID it hit, late hits too (spec 9 issue_ids)"),
                () -> assertEquals(
                        List.of(
                                ReadSeeds.md5(ANA) + "|0.65|",
                                ReadSeeds.md5(BEN) + "|0.38|",
                                ReadSeeds.md5(CAL) + "|0.12|odd_shape|active"),
                        strings(seed, """
                                SELECT md5(user_key) || '|' || p_nominal || '|' || COALESCE(set_aside_reason, '')
                                       || CASE WHEN stage IS NULL THEN '|' || state ELSE '' END
                                  FROM end_user WHERE project_id = :pid ORDER BY days_silent DESC
                                """)),
                () -> assertEquals(List.of("shape:usr/9|" + (D - 25) + "||open|14|0.4|0.1|0.6"), strings(seed, """
                                SELECT scope || '|' || start_day || '|' || COALESCE(end_day::text, '') || '|' || state
                                       || '|' || members || '|' || i || '|' || i0 || '|' || rho
                                  FROM end_user_silence_event WHERE project_id = :pid
                                """)));
    }

    @Test
    void write_replacesTheLastRun_soNoIssueStateOrEventOutlivesIt() {
        ReadSeeds seed = seed("eu-write-replace");
        Snapshot first = notComputed(D - 1);
        writer.write(
                seed.pid(),
                new Run(
                        first,
                        List.of(new Issue(
                                "lin-old",
                                seed.id("c1"),
                                List.of(),
                                null,
                                2,
                                null,
                                null,
                                null,
                                null,
                                Verdict.PAGE_REFUSED,
                                false,
                                null,
                                null,
                                blankDiagnostics())),
                        List.of(new IssueHit("lin-old", ANA, D - 5, false)),
                        List.of(new UserState(ANA, UserIdState.UNKNOWN, null, null, 0.2, 0.2, 3, null, null)),
                        List.of(new SilenceEvent("app", D - 40, D - 30, SilenceState.PROVEN_BREAK, 9, 0.3, 0.1, 0.5))));

        writer.write(seed.pid(), new Run(notComputed(D), List.of(), List.of(), List.of(), List.of()));

        assertAll(
                () -> assertEquals(
                        List.of((D - 1) + "|not_computed", D + "|not_computed"),
                        strings(seed, """
                                SELECT day || '|' || page_state FROM end_user_snapshot WHERE project_id = :pid ORDER BY day
                                """),
                        "older snapshots stay"),
                () -> assertEquals(List.of("0|0|0"), strings(seed, """
                                SELECT (SELECT count(*) FROM end_user_issue WHERE project_id = :pid) || '|'
                                       || (SELECT count(*) FROM end_user_issue_hit WHERE project_id = :pid) || '|'
                                       || (SELECT count(*) FROM end_user_silence_event WHERE project_id = :pid)
                                """)),
                () -> assertEquals(List.of("0"), strings(seed, """
                                SELECT count(*) FROM end_user WHERE project_id = :pid
                                   AND (state IS NOT NULL OR p_stop IS NOT NULL OR p_nominal IS NOT NULL
                                        OR days_silent IS NOT NULL)
                                """), "a user ID this run did not judge has no state"));
    }

    private static Snapshot notComputed(int day) {
        return new Snapshot(
                day,
                "v3",
                PageState.NOT_COMPUTED,
                null,
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
                null);
    }

    private static IssueDiagnostics blankDiagnostics() {
        return new IssueDiagnostics(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private List<String> strings(ReadSeeds seed, String sql) {
        return jdbc.sql(sql).param("pid", seed.pid()).query(String.class).list();
    }
}

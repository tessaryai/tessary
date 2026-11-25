// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.hits.HitFinding;
import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.read.CaseHitRepository.CaseFinding;
import ai.tessary.endusers.read.CaseHitRepository.CaseHits;
import ai.tessary.endusers.read.CaseHitRepository.CaseRefs;
import ai.tessary.endusers.read.CaseHitRepository.FindingRoles;
import ai.tessary.endusers.read.CaseHitRepository.Pick;
import ai.tessary.endusers.read.EndUserEraseRepository.Erased;
import ai.tessary.endusers.read.EndUserListRepository.IdRow;
import ai.tessary.endusers.read.IssueReadRepository.IssueRow;
import ai.tessary.endusers.read.IssueReadRepository.SilentAfter;
import ai.tessary.endusers.read.IssueReadRepository.SilentIdRow;
import ai.tessary.endusers.read.IssueReadRepository.SilentRest;
import ai.tessary.endusers.read.SnapshotReadRepository.SnapshotRow;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every read repository method against real Postgres, each column of a seeded row read back. A column mapped to the
 * wrong field, a jsonb key the judge records do not carry, or a code not parsed from its wire form fails here.
 */
@SpringBootTest
record EndUserReadRepositoryTest(
        TenantService tenants,
        JdbcClient jdbc,
        SnapshotReadRepository snapshots,
        IssueReadRepository issues,
        EndUserListRepository ids,
        CaseHitRepository caseHits,
        EndUserEraseRepository eraser) {

    private static final int D = 20_400;

    private ReadSeeds seed(String name) {
        return new ReadSeeds(
                jdbc, TenantFixture.bootstrap(tenants, name).project().id());
    }

    @Test
    void snapshot_latest_readsEveryColumn() {
        ReadSeeds seed = seed("eu-repo-snapshot");
        seed.snapshot(D - 1, "too_early");
        seed.snapshot(
                D,
                "partial_coverage",
                "low_id_share",
                "{\"share\":0.62}",
                "[{\"code\":\"id_loss\",\"numbers\":{\"onset_day\":20370.0},\"route_nos\":[2,5]}]",
                "[{\"code\":\"no_anchor\",\"numbers\":{},\"never_cut\":true,\"ids\":0}]",
                "{\"named_right\":0.9,\"named_found\":null,\"caveat\":true}",
                "{\"active\":10,\"new\":2,\"silent\":3,\"held\":1,\"stopped_named\":4,\"stopped_expected\":4.5,"
                        + "\"stopped_low\":3.0,\"stopped_high\":6.0,\"counted\":17}",
                "{\"odd_shape\":6}",
                "expected_only",
                "{\"expected\":4.5}");

        SnapshotRow row = snapshots.latest(seed.pid()).orElseThrow();

        assertEquals(
                new SnapshotRow(
                        D,
                        PageState.PARTIAL_COVERAGE,
                        PageStateReason.LOW_ID_SHARE,
                        Map.of("share", 0.62),
                        List.of(new Banner(BannerCode.ID_LOSS, Map.of("onset_day", 20_370.0), List.of(2, 5))),
                        List.of(new LimitLine(LimitCode.NO_ANCHOR, Map.of(), true, 0)),
                        new TrustNumbers(0.9, null, true),
                        new StageCounts(10, 2, 3, 1, 4, 4.5, 3.0, 6.0, 17),
                        Map.of(SetAsideReason.ODD_SHAPE, 6),
                        StopStageState.EXPECTED_ONLY,
                        Map.of("expected", 4.5)),
                row);
    }

    @Test
    void snapshot_latest_readsNullColumnsAsNull_andHistoryStartDay() {
        ReadSeeds seed = seed("eu-repo-snapshot-nulls");
        ReadSeeds unfixed = seed("eu-repo-snapshot-unfixed");
        ReadSeeds none = seed("eu-repo-snapshot-none");
        seed.project(20_100);
        unfixed.project(null);
        seed.snapshot(D, "no_user_id");

        assertAll(
                () -> assertEquals(
                        new SnapshotRow(
                                D,
                                PageState.NO_USER_ID,
                                null,
                                Map.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        snapshots.latest(seed.pid()).orElseThrow()),
                () -> assertEquals(Optional.empty(), snapshots.latest(none.pid())),
                () -> assertEquals(Optional.of(20_100), snapshots.historyStartDay(seed.pid())),
                () -> assertEquals(Optional.empty(), snapshots.historyStartDay(unfixed.pid())),
                () -> assertEquals(Optional.empty(), snapshots.historyStartDay(none.pid())));
    }

    @Test
    void issues_and_forCases_readEveryColumn() {
        ReadSeeds seed = seed("eu-repo-issues");
        seed.caseRow("c-old", 4, "classifier", true);
        seed.caseRow("c-now", 5, "classifier", false);
        seed.finding("f-old", "tool_error", "c-old");
        seed.finding("f-now", "tool_error", "c-now");
        seed.issue(
                "lineage-1",
                D,
                "c-now",
                new String[] {"f-old", "f-now"},
                3,
                112,
                "evidence_of_loss",
                96,
                23.5,
                11.0,
                35.0,
                "group-1",
                "[{\"week\":2,\"hit\":0.79,\"similar\":0.94,\"band_low\":0.75,\"band_high\":0.83,"
                        + "\"at_risk_hit\":90,\"at_risk_similar\":880}]",
                "{\"stopped\":41.0,\"expected\":18.0,\"upper\":30.0,\"h\":21,\"late_hits\":8,\"unmatched\":7,"
                        + "\"t_vis\":18,\"pop_high\":4.2,\"first_evidence_day\":20371,\"estimated\":104,\"accounts\":3,"
                        + "\"account_lines\":[{\"account\":1,\"ids\":6,\"hit\":4,\"silent\":3,"
                        + "\"silent_since_day\":20390}]}");
        IssueRow expected = new IssueRow(
                "lineage-1",
                D,
                seed.id("c-now"),
                5,
                "title c-now",
                "classifier",
                "label c-now",
                3,
                112,
                96,
                23.5,
                11.0,
                35.0,
                Verdict.EVIDENCE_OF_LOSS,
                true,
                "group-1",
                List.of(new CurvePoint(2, 0.79, 0.94, 0.75, 0.83, 90, 880)),
                new IssueDiagnostics(
                        41.0,
                        18.0,
                        30.0,
                        21,
                        8,
                        7,
                        18,
                        4.2,
                        20_371,
                        104,
                        3,
                        List.of(new AccountLine(1, 6, 4, 3, 20_390))));

        assertAll(
                () -> assertEquals(List.of(expected), issues.issues(seed.pid(), D)),
                () -> assertEquals(List.of(), issues.issues(seed.pid(), D - 1)),
                () -> assertEquals(
                        Map.of(seed.id("c-now"), expected, seed.id("c-old"), expected),
                        issues.forCases(
                                seed.pid(), D, List.of(seed.id("c-now"), seed.id("c-old"), seed.id("c-missing"))),
                        "an older case of the lineage finds the issue through its finding"));
    }

    @Test
    void silentIds_and_silentCount_readEveryColumn() {
        ReadSeeds seed = seed("eu-repo-silent");
        byte[] a = ReadSeeds.key("a");
        byte[] b = ReadSeeds.key("b");
        byte[] c = ReadSeeds.key("c");
        seed.endUser(a, "a", "stopped", "stopped", null, "stopped", 40, 0.9, null);
        seed.endUser(b, null, "silent", "active", "overdue", null, null, 0.4, null);
        seed.endUser(c, "c", "active", "active", null, null, 1, 0.1, null);
        for (byte[] k : List.of(a, b, c)) seed.issueHit("lineage-1", k, D - 30);

        List<SilentIdRow> rows = issues.silentIds(seed.pid(), "lineage-1", null, 10);
        List<SilentIdRow> after = issues.silentIds(
                seed.pid(), "lineage-1", new SilentAfter(D - 30, 40, rows.get(0).tie()), 10);

        assertAll(
                () -> assertEquals(
                        List.of(
                                new SilentIdRow("a", D - 30, 40, Stage.STOPPED, Grade.STOPPED, 40, ReadSeeds.md5(a)),
                                new SilentIdRow(null, D - 30, null, Stage.SILENT, null, -1, ReadSeeds.md5(b))),
                        rows),
                () -> assertEquals(List.of(rows.get(1)), after),
                () -> assertEquals(2, issues.silentCount(seed.pid(), "lineage-1")),
                () -> assertEquals(
                        new SilentRest(1, D - 30, D - 30),
                        issues.silentRest(
                                seed.pid(),
                                "lineage-1",
                                new SilentAfter(D - 30, 40, rows.get(0).tie())),
                        "the rest after a row is the rows the next page would read"),
                () -> assertEquals(
                        new SilentRest(0, null, null),
                        issues.silentRest(
                                seed.pid(),
                                "lineage-1",
                                new SilentAfter(D - 30, -1, rows.get(1).tie()))));
    }

    /** The bug: a banner names a route by a trace name of another project, or prints a NULL trace name. */
    @Test
    void routeNames_ofTheProjectOnly_withATraceName() {
        ReadSeeds seed = seed("eu-repo-routes");
        ReadSeeds other = seed("eu-repo-routes-other");
        seed.route(2, "companion.reply", "site-a");
        seed.route(3, "companion.reply", "site-b");
        seed.route(4, null, "site-a");
        other.route(5, "billing.charge", "site-a");

        assertEquals(
                Map.of(2, "companion.reply", 3, "companion.reply"),
                snapshots.routeNames(seed.pid(), List.of(2, 3, 4, 5)));
    }

    @Test
    void idPage_readsEveryColumn() {
        ReadSeeds seed = seed("eu-repo-ids");
        seed.caseRow("c-1", 9, "classifier", false);
        seed.issue("lineage-1", D, "c-1", new String[] {}, 1, "few_accounts");
        byte[] k = ReadSeeds.key("user-1");
        seed.endUser(k, "user-1", "stopped", "stopped", "held", "probably_stopped", 33, 0.74, null);
        seed.issueHit("lineage-1", k, D - 40);

        assertEquals(
                List.of(new IdRow(
                        "user-1",
                        UserIdState.STOPPED,
                        Substate.HELD,
                        Grade.PROBABLY_STOPPED,
                        33,
                        0.74,
                        List.of("C-9"),
                        33,
                        ReadSeeds.md5(k))),
                ids.page(seed.pid(), Stage.STOPPED, null, 10));
    }

    @Test
    void caseHits_readEveryColumn() {
        ReadSeeds seed = seed("eu-repo-case-hits");
        seed.caseRow("c-1", 1, "tool_error", false);
        seed.finding("f-1", "tool_error", "c-1");
        seed.finding("f-quiet", "tool_error", "c-1");
        byte[] k1 = ReadSeeds.key("user-1");
        byte[] k2 = ReadSeeds.key("user-2");
        seed.hit("f-1", "witness", k1, 20_310, 1, 2);
        seed.hit("f-1", "witness", k2, 20_305, 2, 1);
        seed.hit("f-1", "witness", ReadSeeds.NO_USER_KEY, 20_300, 1, 1);
        seed.hit("f-1", "member", k2, 20_300, 1, 4);
        seed.summary("f-1", 4, 1);
        seed.endUser(k2, "user-2", null, null, null, null, null, null, "aggregate");
        seed.foldedUser(k1, "user-1");
        List<Pick> picks = List.of(new Pick("c-1", seed.id("f-1"), "witness"));

        assertAll(
                () -> assertEquals(
                        List.of(new FindingRoles(
                                seed.id("f-1"),
                                new HitFinding("tool_error", "tool_error", false),
                                Set.of("witness", "member"))),
                        caseHits.findingRoles(seed.pid(), List.of(seed.id("f-1"), seed.id("f-quiet")))),
                () -> assertEquals(
                        List.of(new CaseHits("c-1", 2, 20_305)), caseHits.caseHits(seed.pid(), picks, false)),
                () -> assertEquals(
                        List.of(new CaseHits("c-1", 1, 20_310)),
                        caseHits.caseHits(seed.pid(), picks, true),
                        "the set-aside user-2 is not covered"),
                () -> assertEquals(
                        List.of(new CaseRefs("c-1", 4, 1)),
                        caseHits.caseRefs(
                                seed.pid(),
                                List.of(
                                        new CaseFinding("c-1", seed.id("f-1")),
                                        new CaseFinding("c-1", seed.id("f-quiet"))))));
    }

    @Test
    void erase_and_salt_readEveryColumn() {
        ReadSeeds seed = seed("eu-repo-erase");
        ReadSeeds none = seed("eu-repo-erase-none");
        seed.project(null);
        byte[] k = ReadSeeds.key("user-1");
        byte[] other = ReadSeeds.key("user-2");
        seed.foldedUser(k, "user-1");
        seed.day(k, 20_300);
        seed.day(k, 20_301);
        seed.day(other, 20_301);
        seed.hit("f-1", "witness", k, 20_300, 1, 1);
        seed.issueHit("lineage-1", k, 20_300);
        seed.aliasEdge(k, other, 20_300);
        seed.aliasEdge(other, k, 20_301);

        Erased erased = eraser.erase(seed.pid(), k);

        assertAll(
                () -> assertArrayEquals(ReadSeeds.SALT, eraser.salt(seed.pid()).orElseThrow()),
                () -> assertTrue(eraser.salt(none.pid()).isEmpty()),
                () -> assertEquals(new Erased(1, 2, 1, 1, 2), erased),
                () -> assertEquals(7, erased.total()),
                () -> assertEquals(1, seed.rows("end_user_day")));
    }
}

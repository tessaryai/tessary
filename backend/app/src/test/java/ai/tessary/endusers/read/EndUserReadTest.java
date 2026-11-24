// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.read.EndUserDtos.EndUserCaseBlock;
import ai.tessary.endusers.read.EndUserDtos.EndUserEraseRequest;
import ai.tessary.endusers.read.EndUserDtos.EndUserHitCount;
import ai.tessary.endusers.read.EndUserDtos.EndUserIdPage;
import ai.tessary.endusers.read.EndUserDtos.EndUserIdRow;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssue;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueList;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueRow;
import ai.tessary.endusers.read.EndUserDtos.EndUserOverview;
import ai.tessary.endusers.read.EndUserDtos.EndUserSilentId;
import ai.tessary.endusers.read.EndUserDtos.EndUserSilentIds;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.EndUserError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TenantFixture.Setup;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Users view reads and the erasure, through the controller against real Postgres. The rows are written as the
 * fold, the hit sweep and the judge write them, so each test pins what the page shows for one stored state.
 */
@SpringBootTest
record EndUserReadTest(
        TenantService tenants,
        OrgMembershipRepository memberships,
        JdbcClient jdbc,
        EndUserController controller,
        ObjectMapper mapper) {

    private static final int D = 20_400;

    /** The bug: a project with no snapshot answers 404, 500 or zeros instead of the collecting state. */
    @Test
    void overview_onEmptyProject_isCollecting() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-empty");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.caseRow("c-1", 1, "classifier", false);

        EndUserOverview overview = data(controller.overview(owner(s), org(s), proj(s)));
        EndUserIssueList issues = data(controller.issues(owner(s), org(s), proj(s)));
        EndUserIssue issue = data(controller.issue(owner(s), org(s), proj(s), "C-1"));
        EndUserSilentIds silent = data(controller.silentIds(owner(s), org(s), proj(s), seed.id("c-1"), null));
        EndUserIdPage ids = data(controller.ids(owner(s), org(s), proj(s), "stopped", null, 50));
        EndUserCaseBlock block = data(controller.caseBlock(owner(s), org(s), proj(s), seed.id("c-1")));
        List<EndUserHitCount> counts =
                data(controller.hitCounts(owner(s), org(s), proj(s))).cases();

        assertAll(
                () -> assertEquals(PageState.COLLECTING, overview.pageState()),
                () -> assertNull(overview.stages(), "no stage numbers before the first snapshot"),
                () -> assertNull(overview.counted()),
                () -> assertNull(overview.asOf()),
                () -> assertNull(overview.historySince()),
                () -> assertEquals(List.of(), overview.banners()),
                () -> assertEquals(PageState.COLLECTING, issues.pageState()),
                () -> assertEquals(List.of(), issues.issues()),
                () -> assertEquals(PageState.COLLECTING, issue.pageState()),
                () -> assertEquals(seed.id("c-1"), issue.caseId()),
                () -> assertNull(issue.issue(), "a case answers before any judge run"),
                () -> assertNull(silent.verdict()),
                () -> assertEquals(List.of(), silent.ids()),
                () -> assertEquals(PageState.COLLECTING, ids.pageState()),
                () -> assertEquals(List.of(), ids.ids()),
                () -> assertEquals(CountKind.ID_VALUES, block.countKind()),
                () -> assertEquals(0, block.usersHit()),
                () -> assertEquals(PageState.COLLECTING, block.refusal()),
                () -> assertEquals(PageState.COLLECTING, counts.get(0).refusal()),
                () -> assertNull(block.issue()),
                () -> assertEquals(1, counts.size()),
                () -> assertEquals(0, counts.get(0).usersHit()),
                () -> assertEquals(0, seed.rows("end_user_project"), "a read must not create the project's salt row"));
    }

    /** The bug: the stored stage counts lose a number, or {@code counted} is summed again and disagrees with them. */
    @Test
    void overview_returnsStoredStages() throws Exception {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-stages");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.project(20_200);
        seed.snapshot(
                D,
                "ok",
                null,
                "{\"coverage\":0.93}",
                "[{\"code\":\"shared_silence\",\"numbers\":{\"ids\":12.0}}]",
                "[{\"code\":\"lenient_basis\",\"numbers\":{\"ids\":4.0},\"never_cut\":false,\"ids\":4}]",
                "{\"named_right\":0.91,\"named_found\":0.78,\"caveat\":false}",
                "{\"active\":1014,\"new\":61,\"silent\":47,\"held\":3,\"stopped_named\":125,"
                        + "\"stopped_expected\":141.0,\"stopped_low\":128.0,\"stopped_high\":155.0,\"counted\":1186}",
                "{\"aggregate\":2,\"service\":5}",
                "expected_and_named",
                "{\"named\":125.0,\"expected\":141.0}");

        EndUserOverview o = data(controller.overview(owner(s), org(s), proj(s)));
        StageCounts stages = Objects.requireNonNull(o.stages());
        JsonNode wire = mapper.readTree(mapper.writeValueAsString(o));

        assertAll(
                () -> assertEquals(PageState.OK, o.pageState()),
                () -> assertEquals(D, o.asOf()),
                () -> assertEquals(20_200, o.historySince()),
                () -> assertEquals(1014, stages.active()),
                () -> assertEquals(61, stages.newIds()),
                () -> assertEquals(47, stages.silent()),
                () -> assertEquals(125, stages.stoppedNamed()),
                () -> assertEquals(141.0, stages.stoppedExpected()),
                () -> assertEquals(1186, o.counted()),
                () -> assertEquals(Map.of(SetAsideReason.AGGREGATE, 2, SetAsideReason.SERVICE, 5), o.setAside()),
                () -> assertEquals(StopStageState.EXPECTED_AND_NAMED, o.stopStageState()),
                () -> assertEquals(BannerCode.SHARED_SILENCE, o.banners().get(0).code()),
                () -> assertEquals(LimitCode.LENIENT_BASIS, o.limits().get(0).code()),
                () -> assertEquals(0.91, Objects.requireNonNull(o.trust()).namedRight()),
                () -> assertEquals(2, wire.path("set_aside").path("aggregate").asInt(), "codes leave in wire form"),
                () -> assertEquals(61, wire.path("stages").path("new").asInt()),
                () -> assertEquals(
                        "expected_and_named", wire.path("stop_stage_state").asText()));
    }

    /** The bug: a lineage with no case, or one from an older judge run, is listed, or the rank order is lost. */
    @Test
    void issues_listCasesOnly_inStoredRank() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-issues");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.caseRow("c-a", 11, "classifier", false);
        seed.caseRow("c-b", 12, "classifier", false);
        seed.caseRow("c-old", 13, "classifier", true);
        seed.finding("f-a", "tool_error", "c-a");
        seed.finding("f-b", "tool_error", "c-b");
        seed.finding("f-loose", "tool_error", null);
        seed.finding("f-old", "tool_error", "c-old");
        byte[] k1 = ReadSeeds.key("user-1");
        seed.foldedUser(k1, "user-1");
        seed.hit("f-a", "witness", k1, 20_330, 1, 1);
        seed.snapshot(D - 1, "ok");
        seed.snapshot(D, "ok");
        seed.issue(
                "lineage-a",
                D,
                "c-a",
                new String[] {"f-a"},
                2,
                112,
                "evidence_of_loss",
                96,
                23.0,
                11.0,
                35.0,
                null,
                "[{\"week\":1,\"hit\":0.88,\"similar\":0.97,\"band_low\":0.84,\"band_high\":0.92,"
                        + "\"at_risk_hit\":96,\"at_risk_similar\":900}]",
                "{\"stopped\":41.0,\"expected\":18.0,\"h\":21,\"late_hits\":8,\"unmatched\":8}");
        seed.issue("lineage-b", D, "c-b", new String[] {"f-b"}, 1, "not_enough_evidence");
        seed.issue("lineage-loose", D, null, new String[] {"f-loose"}, 3, "not_enough_evidence");
        seed.issue("lineage-old", D - 1, "c-old", new String[] {"f-old"}, 1, "not_enough_evidence");

        EndUserIssueList list = data(controller.issues(owner(s), org(s), proj(s)));
        List<EndUserIssueRow> rows = list.issues();
        EndUserIssueRow lossRow = rows.get(1);

        assertAll(
                () -> assertEquals(
                        List.of("C-12", "C-11"),
                        rows.stream().map(EndUserIssueRow::caseRef).toList()),
                () -> assertEquals(D, list.asOf()),
                () -> assertEquals(Verdict.EVIDENCE_OF_LOSS, lossRow.issue().verdict()),
                () -> assertEquals(112, lossRow.issue().usersHit()),
                () -> assertEquals(23.0, lossRow.issue().lost()),
                () -> assertEquals(41.0, lossRow.issue().diagnostics().stopped()),
                () -> assertEquals(8, lossRow.issue().diagnostics().lateHits()),
                () -> assertEquals(
                        96,
                        Objects.requireNonNull(lossRow.issue().curve()).get(0).atRiskHit()),
                () -> assertEquals(20_330, lossRow.sinceDay()),
                () -> assertNull(rows.get(0).sinceDay(), "no hit, no first hit day"));
    }

    /** The bug: the drawer names user IDs for a verdict that claims no loss, or lists active ones. */
    @Test
    void silentIds_emptyForVerdictsThatMakeNoClaim() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-silent");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.caseRow("c-q", 1, "classifier", false);
        seed.caseRow("c-l", 2, "classifier", false);
        seed.snapshot(D, "ok");
        seed.issue("lineage-q", D, "c-q", new String[] {}, 2, "not_enough_evidence");
        seed.issue("lineage-l", D, "c-l", new String[] {}, 1, "evidence_of_loss");
        byte[] u1 = ReadSeeds.key("u1");
        byte[] u2 = ReadSeeds.key("u2");
        byte[] u3 = ReadSeeds.key("u3");
        byte[] u4 = ReadSeeds.key("u4");
        seed.stoppedUser(u1, "u1", 40);
        seed.endUser(u2, "u2", "silent", "active", "overdue", null, 19, 0.4, null);
        seed.endUser(u3, null, "stopped", "stopped", null, "probably_stopped", 52, 0.7, null);
        seed.endUser(u4, "u4", "active", "active", null, null, 0, 0.05, null);
        for (byte[] k : List.of(u1, u3, u4)) seed.issueHit("lineage-l", k, D - 56);
        seed.issueHit("lineage-l", u2, D - 35);
        seed.issueHit("lineage-q", u1, D - 56);

        EndUserSilentIds none = data(controller.silentIds(owner(s), org(s), proj(s), seed.id("c-q"), null));
        EndUserSilentIds loss = data(controller.silentIds(owner(s), org(s), proj(s), "C-2", null));
        List<EndUserSilentId> rows = loss.ids();

        assertAll(
                () -> assertEquals(Verdict.NOT_ENOUGH_EVIDENCE, none.verdict()),
                () -> assertEquals(List.of(), none.ids()),
                () -> assertEquals(Verdict.EVIDENCE_OF_LOSS, loss.verdict()),
                () -> assertEquals(
                        Arrays.asList(null, "u1", "u2"),
                        rows.stream().map(EndUserSilentId::idText).toList(),
                        "oldest hit first, then longest silent; active u4 is left out"),
                () -> assertEquals(
                        List.of(8, 8, 5),
                        rows.stream().map(EndUserSilentId::weeksSinceHit).toList()),
                () -> assertEquals(
                        List.of(52, 40, 19),
                        rows.stream().map(EndUserSilentId::daysSilent).toList()),
                () -> assertEquals(Grade.PROBABLY_STOPPED, rows.get(0).grade()),
                () -> assertEquals(Stage.SILENT, rows.get(2).stage()),
                () -> assertNull(loss.nextCursor()),
                () -> assertEquals(
                        3,
                        data(controller.issue(owner(s), org(s), proj(s), seed.id("c-l")))
                                .silentNow()),
                () -> assertNull(
                        data(controller.issue(owner(s), org(s), proj(s), "C-1")).silentNow()));
    }

    /** The bug: a tie in first hit day and days silent at the drawer's page edge repeats or drops a user ID. */
    @Test
    void silentIds_keysetPage_acrossTies() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-silent-pages");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.caseRow("c-l", 1, "classifier", false);
        seed.snapshot(D, "ok");
        seed.issue("lineage-l", D, "c-l", new String[] {}, 1, "evidence_of_loss");
        for (int i = 0; i < 60; i++) {
            byte[] k = ReadSeeds.key("silent-" + i);
            seed.stoppedUser(k, "silent-" + i, 40 - (i % 30) / 3);
            seed.issueHit("lineage-l", k, i < 30 ? D - 56 : D - 35);
        }

        EndUserSilentIds first = data(controller.silentIds(owner(s), org(s), proj(s), "C-1", null));
        EndUserSilentIds second = data(controller.silentIds(owner(s), org(s), proj(s), "C-1", first.nextCursor()));
        List<EndUserSilentId> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        List<Integer> weeks = all.stream().map(EndUserSilentId::weeksSinceHit).toList();

        assertAll(
                () -> assertEquals(50, first.ids().size()),
                () -> assertEquals(10, second.ids().size()),
                () -> assertNull(second.nextCursor()),
                () -> assertEquals(
                        60, all.stream().map(EndUserSilentId::idText).distinct().count()),
                () -> assertEquals(30, weeks.stream().filter(w -> w == 8).count()),
                () -> assertEquals(8, weeks.get(29), "the oldest hits come first"),
                () -> assertEquals(5, weeks.get(30)),
                () -> assertEquals(40, all.get(30).daysSilent(), "within one hit week, longest silent first"),
                () -> assertCode(
                        CommonError.INVALID_PARAMETER,
                        () -> controller.silentIds(owner(s), org(s), proj(s), "C-1", "not a cursor")),
                () -> assertCode(
                        CommonError.INVALID_PARAMETER,
                        () -> controller.ids(owner(s), org(s), proj(s), "stopped", "Zm9v", 50)));
    }

    /**
     * The bug: member rows or the all-zero key count as user IDs hit, or a metric shift shows zero user IDs hit
     * instead of having none.
     */
    @Test
    void hitCounts_beforeFirstSnapshot_countIdValues() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-hits");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seedToolErrorHits(seed);
        seed.caseRow("c-m", 22, "metric_drift", false);
        seed.finding("f-m", "duration_drift", "c-m");

        Map<String, EndUserHitCount> counts =
                byCase(data(controller.hitCounts(owner(s), org(s), proj(s))).cases());
        EndUserCaseBlock block = data(controller.caseBlock(owner(s), org(s), proj(s), "C-21"));
        EndUserCaseBlock metric = data(controller.caseBlock(owner(s), org(s), proj(s), seed.id("c-m")));

        assertAll(
                () -> assertEquals(3, counts.get("C-21").usersHit()),
                () -> assertEquals(CountKind.ID_VALUES, counts.get("C-21").countKind()),
                () -> assertNull(counts.get("C-21").lost()),
                () -> assertEquals(CountKind.NOT_APPLICABLE, counts.get("C-22").countKind()),
                () -> assertNull(counts.get("C-22").usersHit()),
                () -> assertEquals(3, block.usersHit()),
                () -> assertEquals(CountKind.ID_VALUES, block.countKind()),
                () -> assertEquals(20_330, block.sinceDay()),
                () -> assertEquals(5, block.flaggedTraces()),
                () -> assertEquals(1, block.flaggedTracesNoUserId()),
                () -> assertEquals(CountKind.NOT_APPLICABLE, metric.countKind()),
                () -> assertNull(metric.usersHit()));
    }

    /**
     * The bug: after the first snapshot a set-aside user ID still counts, the loss numbers show for a verdict that
     * claims none, or the chip keeps the numbers of a refused page.
     */
    @Test
    void hitCounts_afterFirstSnapshot_countCoveredOnly() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-covered");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seedToolErrorHits(seed);
        seed.caseRow("c-n", 23, "tool_error", false);
        seed.finding("f-n", "tool_error", "c-n");
        seed.foldedUser(ReadSeeds.key("user-1"), "user-1");
        seed.foldedUser(ReadSeeds.key("user-2"), "user-2");
        seed.endUser(ReadSeeds.key("user-3"), "user-3", null, null, null, null, null, null, "service");
        seed.snapshot(D, "ok");
        seed.issue(
                "lineage-t",
                D,
                "c-t",
                new String[] {"f-t"},
                1,
                2,
                "evidence_of_loss",
                2,
                23.0,
                11.0,
                35.0,
                null,
                null,
                "{}");
        seed.issue(
                "lineage-n",
                D,
                "c-n",
                new String[] {"f-n"},
                2,
                0,
                "not_enough_evidence",
                0,
                5.0,
                1.0,
                9.0,
                null,
                null,
                "{}");

        Map<String, EndUserHitCount> ok =
                byCase(data(controller.hitCounts(owner(s), org(s), proj(s))).cases());
        seed.snapshot(D + 1, "too_early");
        Map<String, EndUserHitCount> refused =
                byCase(data(controller.hitCounts(owner(s), org(s), proj(s))).cases());

        assertAll(
                () -> assertEquals(2, ok.get("C-21").usersHit(), "user-3 is set aside"),
                () -> assertEquals(CountKind.COVERED, ok.get("C-21").countKind()),
                () -> assertNull(ok.get("C-21").refusal()),
                () -> assertEquals(23.0, ok.get("C-21").lost()),
                () -> assertEquals(11.0, ok.get("C-21").low()),
                () -> assertEquals(35.0, ok.get("C-21").high()),
                () -> assertNull(ok.get("C-23").lost(), "no loss numbers without evidence of loss"),
                () -> assertEquals(PageState.TOO_EARLY, refused.get("C-21").refusal()),
                () -> assertNull(refused.get("C-21").lost(), "the newest judge run stored no issue for the case"));
    }

    /** The bug: a read resolves another project's slug or case, so one tenant reads another's user IDs. */
    @Test
    void reads_areTenantIsolated() {
        Setup mine = TenantFixture.bootstrap(tenants, "eu-read-mine");
        Setup other = TenantFixture.bootstrap(tenants, "eu-read-other");
        ReadSeeds theirs = new ReadSeeds(jdbc, other.project().id());
        theirs.caseRow("c-theirs", 7, "classifier", false);
        theirs.snapshot(D, "ok");
        theirs.issue("lineage-theirs", D, "c-theirs", new String[] {}, 1, "evidence_of_loss");
        TenantContext me = owner(mine);
        String myOrg = org(mine);
        String theirProject = proj(other);

        List<Executable> foreignSlug = List.of(
                () -> controller.overview(me, myOrg, theirProject),
                () -> controller.issues(me, myOrg, theirProject),
                () -> controller.issue(me, myOrg, theirProject, theirs.id("c-theirs")),
                () -> controller.silentIds(me, myOrg, theirProject, theirs.id("c-theirs"), null),
                () -> controller.ids(me, myOrg, theirProject, "stopped", null, 50),
                () -> controller.caseBlock(me, myOrg, theirProject, theirs.id("c-theirs")),
                () -> controller.hitCounts(me, myOrg, theirProject),
                () -> controller.erase(me, myOrg, theirProject, new EndUserEraseRequest("user-1")));
        List<Executable> foreignCase = List.of(
                () -> controller.issue(me, myOrg, proj(mine), theirs.id("c-theirs")),
                () -> controller.issue(me, myOrg, proj(mine), "C-7"),
                () -> controller.silentIds(me, myOrg, proj(mine), theirs.id("c-theirs"), null),
                () -> controller.caseBlock(me, myOrg, proj(mine), "C-7"));

        for (Executable call : foreignSlug) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class, call);
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
        for (Executable call : foreignCase) assertCode(EndUserError.NOT_FOUND, call);
    }

    /** The bug: a tie in days silent across a page edge repeats or drops a user ID, or another stage leaks in. */
    @Test
    void ids_keysetPage() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-ids");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.snapshot(D, "ok");
        Set<String> keyHex = new HashSet<>();
        for (int i = 0; i < 120; i++) {
            byte[] k = ReadSeeds.key("stopped-" + i);
            keyHex.add(HexFormat.of().formatHex(k));
            seed.stoppedUser(k, "stopped-" + i, 200 - i / 3);
        }
        for (int i = 0; i < 5; i++) {
            seed.endUser(ReadSeeds.key("active-" + i), "active-" + i, "active", "active", null, null, 500, 0.0, null);
        }

        List<EndUserIdRow> all = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        String cursor = null;
        do {
            EndUserIdPage page = data(controller.ids(owner(s), org(s), proj(s), "stopped", cursor, 50));
            all.addAll(page.ids());
            sizes.add(page.ids().size());
            cursor = page.nextCursor();
            if (cursor != null) cursors.add(cursor);
        } while (cursor != null);
        List<Integer> silent = all.stream().map(EndUserIdRow::daysSilent).toList();

        assertAll(
                () -> assertEquals(List.of(50, 50, 20), sizes),
                () -> assertEquals(
                        120,
                        all.stream().map(EndUserIdRow::idText).distinct().count(),
                        "no user ID repeats across pages"),
                () -> assertEquals(silent.stream().sorted((a, b) -> b - a).toList(), silent, "longest silent first"),
                () -> assertEquals(200, silent.get(0)),
                () -> assertTrue(
                        cursors.stream()
                                .map(EndUserReadTest::decoded)
                                .noneMatch(c -> keyHex.stream().anyMatch(c::contains)),
                        "a cursor never carries a user key"),
                () -> assertCode(
                        CommonError.INVALID_PARAMETER,
                        () -> controller.ids(owner(s), org(s), proj(s), "stopped", null, 201)),
                () -> assertCode(
                        CommonError.INVALID_PARAMETER,
                        () -> controller.ids(owner(s), org(s), proj(s), "stopped", null, 0)));
    }

    /** The bug: "Hit by" names an issue whose verdict claims no loss. */
    @Test
    void ids_hitBy_namesOnlyIssuesThatClaimALoss() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-hit-by");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.caseRow("c-loss", 31, "classifier", false);
        seed.caseRow("c-few", 32, "classifier", false);
        seed.caseRow("c-none", 33, "classifier", false);
        seed.snapshot(D, "ok");
        seed.issue("lineage-loss", D, "c-loss", new String[] {}, 1, "evidence_of_loss");
        seed.issue("lineage-few", D, "c-few", new String[] {}, 2, "few_accounts");
        seed.issue("lineage-none", D, "c-none", new String[] {}, 3, "not_enough_evidence");
        byte[] k = ReadSeeds.key("user-1");
        seed.stoppedUser(k, "user-1", 30);
        for (String lineage : List.of("lineage-loss", "lineage-few", "lineage-none")) seed.issueHit(lineage, k, D - 40);

        EndUserIdRow row = data(controller.ids(owner(s), org(s), proj(s), "stopped", null, 50))
                .ids()
                .get(0);

        assertEquals(List.of("C-31", "C-32"), row.hitBy());
    }

    /**
     * The bug: an erasure leaves a row of the key behind, deletes another key's rows, or runs for a token or a viewer.
     */
    @Test
    void erase_removesEveryRowOfOneKey_only_andNeedsOrgManage() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-read-erase");
        ReadSeeds seed = new ReadSeeds(jdbc, s.project().id());
        seed.project(20_200);
        byte[] k1 = ReadSeeds.key("user-1");
        byte[] k2 = ReadSeeds.key("user-2");
        byte[] k3 = ReadSeeds.key("user-3");
        seed.caseRow("c-1", 1, "classifier", false);
        seed.finding("f-1", "tool_error", "c-1");
        for (byte[] k : List.of(k1, k2)) {
            seed.foldedUser(k, "id");
            seed.day(k, 20_300);
            seed.hit("f-1", "witness", k, 20_300, 1, 1);
            seed.issueHit("lineage-1", k, 20_300);
        }
        seed.day(k1, 20_301);
        seed.aliasEdge(k1, k2, 20_300);
        seed.aliasEdge(k3, k1, 20_300);
        seed.aliasEdge(k2, k3, 20_300);
        TenantContext token =
                new TenantContext("user-key", null, s.org().id(), s.project().id(), "member", "tok-1", KeyScope.ADMIN);
        TenantContext viewer = session(withRole(s, Role.VIEWER.wire()));

        assertCode(
                EndUserError.ERASE_NEEDS_SESSION,
                () -> controller.erase(token, org(s), proj(s), new EndUserEraseRequest("user-1")));
        ResponseStatusException forbidden = assertThrows(
                ResponseStatusException.class,
                () -> controller.erase(viewer, org(s), proj(s), new EndUserEraseRequest("user-1")));
        assertCode(
                CommonError.INVALID_PARAMETER,
                () -> controller.erase(owner(s), org(s), proj(s), new EndUserEraseRequest("  ")));
        int before = seed.rows("end_user")
                + seed.rows("end_user_day")
                + seed.rows("end_user_hit")
                + seed.rows("end_user_issue_hit")
                + seed.rows("end_user_alias_edge");

        int deleted = data(controller.erase(owner(s), org(s), proj(s), new EndUserEraseRequest(" user-1 ")))
                .deletedRows();

        assertAll(
                () -> assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode()),
                () -> assertEquals(12, before, "the refused calls deleted nothing"),
                () -> assertEquals(7, deleted),
                () -> assertEquals(0, seed.rowsOfKey("end_user", k1)),
                () -> assertEquals(0, seed.rowsOfKey("end_user_day", k1)),
                () -> assertEquals(0, seed.rowsOfKey("end_user_hit", k1)),
                () -> assertEquals(0, seed.rowsOfKey("end_user_issue_hit", k1)),
                () -> assertEquals(1, seed.rows("end_user_alias_edge"), "only the edge without user-1 stays"),
                () -> assertEquals(1, seed.rowsOfKey("end_user", k2)),
                () -> assertEquals(1, seed.rowsOfKey("end_user_day", k2)),
                () -> assertEquals(1, seed.rowsOfKey("end_user_hit", k2)),
                () -> assertEquals(1, seed.rowsOfKey("end_user_issue_hit", k2)));
    }

    /**
     * Case c-t (C-21): witness rows on user-1 (two days), user-2 and user-3, one witness ref with no user ID, and
     * member rows on two more user IDs, which are trials, not hits.
     */
    private static void seedToolErrorHits(ReadSeeds seed) {
        seed.caseRow("c-t", 21, "tool_error", false);
        seed.finding("f-t", "tool_error", "c-t");
        seed.hit("f-t", "witness", ReadSeeds.key("user-1"), 20_330, 1, 1);
        seed.hit("f-t", "witness", ReadSeeds.key("user-1"), 20_331, 1, 1);
        seed.hit("f-t", "witness", ReadSeeds.key("user-2"), 20_335, 1, 1);
        seed.hit("f-t", "witness", ReadSeeds.key("user-3"), 20_336, 1, 1);
        seed.hit("f-t", "witness", ReadSeeds.NO_USER_KEY, 20_336, 1, 1);
        seed.hit("f-t", "member", ReadSeeds.key("user-4"), 20_320, 1, 1);
        seed.hit("f-t", "member", ReadSeeds.key("user-5"), 20_320, 1, 1);
        seed.summary("f-t", 5, 1);
    }

    private static Map<String, EndUserHitCount> byCase(List<EndUserHitCount> rows) {
        Map<String, EndUserHitCount> out = new HashMap<>();
        for (EndUserHitCount row : rows) out.put(row.caseRef(), row);
        return out;
    }

    private static String decoded(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    private Principal withRole(Setup s, String role) {
        Principal u = tenants.upsertUserFromWorkos(
                "user_eu_read_" + role + "_" + System.nanoTime(),
                "eu-read+" + role + "+" + System.nanoTime() + "@example.com",
                role,
                null);
        memberships.insert(
                OrgMembership.of(s.org().id(), u.id(), role, Instant.now().toString()));
        return u;
    }

    private static TenantContext owner(Setup s) {
        return session(s.user());
    }

    private static TenantContext session(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
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

    private static void assertCode(ErrorCode code, Executable call) {
        TessaryException ex = assertThrows(TessaryException.class, call);
        assertSame(code, ex.error());
    }
}

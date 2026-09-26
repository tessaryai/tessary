// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.ResolveCaseRequest;
import ai.tessary.cases.CaseDtos.TriageView;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.CaseError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * The case API a person drives from Triage. Every call resolves the path's project first and hands the
 * service only that project's id, so the bugs are a case from another project readable or writable through
 * this project's path, a bucket that files a muted or resolved case under the open queue, and RCA pressable
 * by someone the org does not let spend a strong model's time.
 */
@SpringBootTest
// batch-size=0 parks RcaWorker's drain, as in CaseServiceTest.
@TestPropertySource(properties = {"test.context-group=case-service", "tessary.rca.batch-size=0"})
class CaseControllerTest {

    @Autowired
    CaseController controller;

    @Autowired
    CaseRepository cases;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    @Autowired
    OrgMembershipRepository memberships;

    /**
     * Each case files under its state's bucket: open in the queue, muted beside it, closed in the week's history.
     * Unmute returns it to the queue; a case is read by stored id or quoted reference.
     */
    @Test
    void triageFilesEachCaseUnderItsStateAndACaseReadsByIdOrReference() {
        var fix = TenantFixture.bootstrap(tenants, "case-api-triage");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        CaseRow open = open(fix.project(), "a");
        CaseRow muted = open(fix.project(), "b");
        CaseRow resolved = open(fix.project(), "c");
        CaseRow unmuted = open(fix.project(), "d");

        controller.mute(owner, org, project, muted.id());
        controller.mute(owner, org, project, unmuted.id());
        controller.unmute(owner, org, project, unmuted.id());
        CaseView closed = requireNonNull(controller
                .resolve(owner, org, project, resolved.id(), new ResolveCaseRequest("shipped a fix", null))
                .data());
        TriageView triage =
                requireNonNull(controller.triage(owner, org, project).data());

        assertEquals(CaseRow.State.RESOLVED, closed.state());
        assertEquals(Set.of(open.id(), unmuted.id()), Set.copyOf(ids(triage.cases())));
        assertEquals(List.of(muted.id()), ids(triage.muted()));
        assertEquals(List.of(resolved.id()), ids(triage.recentlyResolved()));
        assertEquals(
                open.id(),
                requireNonNull(controller
                                .get(owner, org, project, open.reference())
                                .data())
                        .caseView()
                        .id());
    }

    /**
     * A case id from another project is not found through this project's path, for a read and for a write:
     * the path's project, never the case id, decides whose data is touched.
     */
    @Test
    void anotherProjectsCaseIsNotFoundThroughThisProjectsPath() {
        var mine = TenantFixture.bootstrap(tenants, "case-api-tenant-a");
        var theirs = TenantFixture.bootstrap(tenants, "case-api-tenant-b");
        CaseRow theirCase = open(theirs.project(), "a");
        TenantContext me = owner(mine);

        assertEquals(
                CaseError.NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> controller.get(
                                        me, mine.org().slug(), mine.project().slug(), theirCase.id()))
                        .error());
        assertEquals(
                CaseError.NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> controller.absorb(
                                        me, mine.org().slug(), mine.project().slug(), theirCase.id()))
                        .error());
        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(theirs.project().id(), theirCase.id())
                        .orElseThrow()
                        .state());
    }

    /**
     * Pressing RCA spends the org's own model on a strong run, so a viewer, who may not manage the org, is refused and
     * the case stays unlocked; the owner's press locks the case and returns the report it queued. Absorbing a
     * case closes it.
     */
    @Test
    void onlyAManagerPressesRcaAndAbsorbClosesTheCase() {
        var fix = TenantFixture.bootstrap(tenants, "case-api-rca");
        String org = fix.org().slug();
        String project = fix.project().slug();
        CaseRow row = open(fix.project(), "a");
        Principal member = tenants.upsertUserFromWorkos(
                "user_case_member_" + System.nanoTime(),
                "case-member+" + System.nanoTime() + "@example.com",
                "m",
                null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), Role.VIEWER.wire(), Instant.now().toString()));
        TenantContext viewerSession = new TenantContext(member.id(), member.email(), null, null, null, null);

        ResponseStatusException refused = assertThrows(
                ResponseStatusException.class, () -> controller.runRca(viewerSession, org, project, row.id()));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals(
                null, cases.findById(fix.project().id(), row.id()).orElseThrow().lockedAt());

        var report = requireNonNull(
                controller.runRca(owner(fix), org, project, row.id()).data());
        assertEquals(JobRow.Status.PENDING, report.status(), "the press queued a run and returned its report");
        assertEquals(
                true, cases.findById(fix.project().id(), row.id()).orElseThrow().lockedAt() != null);

        CaseView absorbed = requireNonNull(
                controller.absorb(owner(fix), org, project, row.id()).data());
        assertEquals(CaseRow.State.RESOLVED, absorbed.state());
        assertEquals(CaseRow.Resolution.ABSORBED, absorbed.resolution());
    }

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    private static List<String> ids(List<CaseView> views) {
        return views.stream().map(CaseView::id).toList();
    }

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** A classifier case backed by a finding, as the forward CHECK requires. */
    private CaseRow open(Project p, String subject) {
        String now = Instant.now().toString();
        String findingId = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        p.id(),
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + subject,
                        "cause-" + subject,
                        1,
                        "cs-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
        CaseDetection detection = new CaseDetection(
                new CaseKey(CaseRow.Detector.CLASSIFIER, CaseRow.SubjectKind.CLASSIFIER, "subject-" + subject, "rate"),
                "subject " + subject,
                null,
                findingId,
                "something happened",
                "because the detector said so",
                0.4,
                Instant.parse("2026-07-01T10:00:00Z"),
                null,
                null,
                null);
        return cases.open(p.id(), detection, Instant.now()).orElseThrow();
    }
}

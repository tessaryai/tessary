// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code fix_pr} against the real table. The bugs: a column written and not read back, a commit that loses the causes
 * the pull request already fixed, a merged or closed state that does not stick, two open pull requests for one case,
 * and a fix line the case trail's kind check refuses.
 */
@RcaParkedSpringBootTest
record FixPrRepositoryTest(
        FixPrRepository prs,
        TenantService tenants,
        FindingRepository findings,
        CaseRepository cases,
        CaseService caseService,
        CaseEventRepository events) {

    private static final Instant T0 = Instant.parse("2026-11-19T12:00:00Z");

    private FixSeeds.Seeded seed(String name) {
        return FixSeeds.caseWithReport(tenants, findings, cases, caseService, name);
    }

    @Test
    void anOpenedPullRequestReadsBackEveryColumnAndACommitAddsItsCauses() {
        FixSeeds.Seeded s = seed("fix-pr-roundtrip");
        String id = prs.insertOpen(
                s.projectId(), s.caseId(), "tessary/fix-c1", 41, "https://pr/41", "h1", List.of("code:a.py"), T0);

        prs.recordCommit(id, "h2", List.of("code:a.py", "prompt:b.md"), T0.plusSeconds(60));

        assertEquals(
                List.of(new FixPrRow(
                        id,
                        s.projectId(),
                        s.caseId(),
                        "tessary/fix-c1",
                        41,
                        "https://pr/41",
                        FixPrRow.State.OPEN,
                        "h2",
                        List.of("code:a.py", "prompt:b.md"),
                        T0.toString(),
                        T0.plusSeconds(60).toString())),
                prs.listByCase(s.projectId(), s.caseId()));
        assertEquals(List.of(), prs.listByCase("other-project", s.caseId()));
    }

    @Test
    void aClosedPullRequestStaysClosedAndTheCaseMayOpenAnotherNewestFirst() {
        FixSeeds.Seeded s = seed("fix-pr-states");
        String first = prs.insertOpen(s.projectId(), s.caseId(), "b1", 41, "https://pr/41", "h1", List.of(), T0);
        prs.markState(first, FixPrRow.State.CLOSED, T0.plusSeconds(1));
        String second = prs.insertOpen(
                s.projectId(), s.caseId(), "b2", 42, "https://pr/42", "h2", List.of(), T0.plusSeconds(2));

        List<FixPrRow> all = prs.listByCase(s.projectId(), s.caseId());

        assertEquals(List.of(second, first), all.stream().map(FixPrRow::id).toList());
        assertEquals(
                List.of(FixPrRow.State.OPEN, FixPrRow.State.CLOSED),
                all.stream().map(FixPrRow::state).toList());
    }

    @Test
    void aCaseCannotHaveTwoOpenPullRequests() {
        FixSeeds.Seeded s = seed("fix-pr-one-open");
        prs.insertOpen(s.projectId(), s.caseId(), "b1", 41, "https://pr/41", "h1", List.of(), T0);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> prs.insertOpen(s.projectId(), s.caseId(), "b2", 42, "https://pr/42", "h2", List.of(), T0));
    }

    @Test
    void theCaseTrailTakesBothFixLines() {
        FixSeeds.Seeded s = seed("fix-pr-trail");

        events.append(s.projectId(), s.caseId(), CaseEventRow.Kind.FIX_PR_OPENED, null, "Opened.", null, T0);
        events.append(
                s.projectId(), s.caseId(), CaseEventRow.Kind.FIX_PR_UPDATED, null, "Added.", null, T0.plusSeconds(1));

        assertEquals(
                List.of(CaseEventRow.Kind.FIX_PR_OPENED, CaseEventRow.Kind.FIX_PR_UPDATED),
                events.listByCase(s.projectId(), s.caseId()).stream()
                        .map(CaseEventRow::kind)
                        .filter(k -> k.startsWith("fix_pr"))
                        .toList());
    }
}

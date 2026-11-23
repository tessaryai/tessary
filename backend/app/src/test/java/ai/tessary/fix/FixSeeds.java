// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.cases.CaseDetection;
import ai.tessary.cases.CaseKey;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.rca.RcaTriggerService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Objects;

/** A project with one case and one pending RCA report on it: the rows every fix row points at. */
final class FixSeeds {

    private FixSeeds() {}

    /** @param reportId a pending report on the case, the subject of a fix run */
    record Seeded(TenantFixture.Setup tenant, String caseId, String findingId, String reportId) {
        String projectId() {
            return tenant.project().id();
        }

        String userId() {
            return tenant.user().id();
        }
    }

    static Seeded caseWithReport(
            TenantService tenants,
            FindingRepository findings,
            CaseRepository cases,
            CaseService caseService,
            String name) {
        var fixture = TenantFixture.bootstrap(tenants, name);
        String pid = fixture.project().id();
        String now = Instant.now().toString();
        String findingId = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.SECRET_LEAK,
                        "clf-a",
                        "cause-a",
                        1,
                        "cs-a",
                        "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}",
                        now,
                        now,
                        now,
                        now))
                .findingId();
        String caseId = cases.open(
                        pid,
                        new CaseDetection(
                                new CaseKey(
                                        CaseRow.Detector.CLASSIFIER, CaseRow.SubjectKind.CLASSIFIER, "p-1", "novelty"),
                                "extraction",
                                "cs-a",
                                findingId,
                                "A step the agent always performs was skipped",
                                "seen on 1 trace",
                                0.4,
                                Instant.parse("2026-07-01T10:00:00Z"),
                                null,
                                null,
                                null),
                        Instant.now())
                .orElseThrow()
                .id();
        String reportId = caseService.runRca(pid, caseId, fixture.user().id()).id();
        return new Seeded(fixture, caseId, findingId, reportId);
    }

    /** Another pending report on the same case, as a rerun makes. */
    static String rerun(RcaTriggerService trigger, Seeded s) {
        return trigger.trigger(s.projectId(), s.findingId(), s.userId(), Ids.ulid())
                .id();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.VersionError;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProjectVersionRepositoryTest {

    @Autowired
    ProjectVersionRepository repo;

    @Autowired
    ProjectVersionService service;

    @Autowired
    TenantService tenants;

    @Autowired
    ProjectVersionController controller;

    @Test
    void findOrMaterialize_isIdempotentPerCommit() {
        String pid = TenantFixture.bootstrap(tenants, "pv-idem").project().id();
        var a = repo.findOrMaterialize(pid, "sha-1", ProjectVersionRow.REASON_PIPELINE_SYNC);
        var b = repo.findOrMaterialize(pid, "sha-1", "a-later-reason");
        assertEquals(a.id(), b.id(), "same commit re-materializes the same row, no duplicate");
        assertEquals(1, repo.listTimeline(pid).size());
        assertEquals(ProjectVersionRow.REASON_PIPELINE_SYNC, b.materializedReason(), "first writer's reason wins");
    }

    @Test
    void reasonPipelineSync_marksGradersSynced() {
        String pid = TenantFixture.bootstrap(tenants, "pv-sync").project().id();
        service.reasonPipelineSync(pid, "sha-2");
        var row = repo.findByCommit(pid, "sha-2").orElseThrow();
        assertEquals(ProjectVersionRow.STATUS_SYNCED, row.gradersStatus());
    }

    /**
     * The bug: the version endpoints read across projects, listing or returning a commit another project
     * synced, instead of answering only for the project in the URL.
     */
    @Test
    void controller_readsOnlyThisProjectsVersions() {
        var fix = TenantFixture.bootstrap(tenants, "pv-controller");
        var other = TenantFixture.bootstrap(tenants, "pv-controller-other");
        service.reasonPipelineSync(fix.project().id(), "sha-own");
        service.reasonPipelineSync(other.project().id(), "sha-other");
        TenantContext owner = new TenantContext(fix.user().id(), null, null, null, null, null);
        String org = fix.org().slug();
        String project = fix.project().slug();

        assertEquals(
                List.of("sha-own"),
                Objects.requireNonNull(controller.timeline(owner, org, project).data()).stream()
                        .map(ProjectVersionDtos.ProjectVersionView::commitSha)
                        .toList());
        assertEquals(
                "sha-own",
                Objects.requireNonNull(
                                controller.get(owner, org, project, "sha-own").data())
                        .commitSha());
        TessaryException foreign =
                assertThrows(TessaryException.class, () -> controller.get(owner, org, project, "sha-other"));
        assertEquals(VersionError.NOT_FOUND, foreign.error());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ProjectVersionRepositoryTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    ProjectVersionRepository repo;

    @Autowired
    ProjectVersionService service;

    @Autowired
    TenantService tenants;

    @Test
    void findOrMaterialize_isIdempotentPerCommit() {
        String pid = TenantFixture.bootstrap(tenants, "pv-idem").project().id();
        var a = repo.findOrMaterialize(pid, "sha-1", ProjectVersionRow.REASON_BENCHMARK);
        var b = repo.findOrMaterialize(pid, "sha-1", ProjectVersionRow.REASON_OBSERVER_FINDING);
        assertEquals(a.id(), b.id(), "same commit re-materializes the same row, no duplicate");
        assertEquals(1, repo.listTimeline(pid).size());
        assertEquals(ProjectVersionRow.REASON_BENCHMARK, b.materializedReason(), "first writer's reason wins");
    }

    @Test
    void reasonPipelineSync_marksGradersSynced() {
        String pid = TenantFixture.bootstrap(tenants, "pv-sync").project().id();
        service.reasonPipelineSync(pid, "sha-2");
        var row = repo.findByCommit(pid, "sha-2").orElseThrow();
        assertEquals(ProjectVersionRow.STATUS_SYNCED, row.gradersStatus());
    }

    @Test
    void aspectStatus_canTransitionToStale() {
        String pid = TenantFixture.bootstrap(tenants, "pv-aspect").project().id();
        service.reasonObserverFinding(pid, "sha-3");
        service.markGraders(pid, "sha-3", ProjectVersionRow.STATUS_STALE);
        var row = repo.findByCommit(pid, "sha-3").orElseThrow();
        assertEquals(ProjectVersionRow.STATUS_STALE, row.gradersStatus());
    }

    @Test
    void timeline_listsMaterializedVersions() {
        String pid = TenantFixture.bootstrap(tenants, "pv-timeline").project().id();
        service.reasonBenchmark(pid, "sha-a");
        service.reasonBenchmark(pid, "sha-b");
        assertEquals(2, service.timeline(pid).size());
        assertTrue(service.timeline(pid).stream().anyMatch(v -> v.commitSha().equals("sha-a")));
    }
}

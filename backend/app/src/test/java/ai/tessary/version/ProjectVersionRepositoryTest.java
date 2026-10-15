// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class ProjectVersionRepositoryTest {

    @Autowired
    ProjectVersionRepository repo;

    @Autowired
    ProjectVersionService service;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    void findOrMaterialize_isIdempotentPerCommit() {
        String pid = TenantFixture.bootstrap(tenants, "pv-idem").project().id();
        var a = repo.findOrMaterialize(pid, "sha-1", ProjectVersionRow.REASON_PIPELINE_SYNC);
        var b = repo.findOrMaterialize(pid, "sha-1", "a-later-reason");
        assertEquals(a.id(), b.id(), "same commit re-materializes the same row, no duplicate");
        assertEquals(
                1L,
                jdbc.sql("SELECT count(*) FROM project_version WHERE project_id = :pid")
                        .param("pid", pid)
                        .query(Long.class)
                        .single());
        assertEquals(ProjectVersionRow.REASON_PIPELINE_SYNC, b.materializedReason(), "first writer's reason wins");
    }

    @Test
    void reasonPipelineSync_marksGradersSynced() {
        String pid = TenantFixture.bootstrap(tenants, "pv-sync").project().id();
        service.reasonPipelineSync(pid, "sha-2");
        var row = repo.findByCommit(pid, "sha-2").orElseThrow();
        assertEquals(ProjectVersionRow.STATUS_SYNCED, row.gradersStatus());
    }
}

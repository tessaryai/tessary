// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class GitIntegrationServiceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    GitIntegrationService service;

    @Autowired
    TenantService tenants;

    private ConnectRequest github() {
        return new ConnectRequest("github", "tessary", "tessary", null, "main", 4242L, null);
    }

    @Test
    void connect_sealsCredentialsAndBindsRepo() {
        String pid = TenantFixture.bootstrap(tenants, "git-connect").project().id();
        GitIntegrationRow row = service.connect(pid, github());
        assertEquals("github", row.provider());
        assertEquals("tessary", row.repoOwner());
        assertNotNull(row.credentialsEnc(), "installation id is sealed at rest");
        assertEquals(row.id(), service.require(pid).id());
    }

    @Test
    void connect_rejectsDuplicateForSameProject() {
        String pid = TenantFixture.bootstrap(tenants, "git-dup").project().id();
        service.connect(pid, github());
        assertThrows(EvalsException.class, () -> service.connect(pid, github()));
    }

    @Test
    void require_throwsWhenUnbound() {
        String pid = TenantFixture.bootstrap(tenants, "git-none").project().id();
        assertThrows(EvalsException.class, () -> service.require(pid));
    }

    @Test
    void delete_removesIntegration() {
        String pid = TenantFixture.bootstrap(tenants, "git-del").project().id();
        service.connect(pid, github());
        assertTrue(service.delete(pid));
        assertTrue(service.find(pid).isEmpty());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GitIntegrationServiceTest {

    @Autowired
    GitIntegrationService service;

    @Autowired
    TenantService tenants;

    @Autowired
    GitIntegrationRepository repo;

    @Autowired
    SecretBox secretBox;

    @Autowired
    ObjectMapper mapper;

    /** A service whose one provider answers however this test needs it to. */
    private GitIntegrationService withProvider(GitProviderClient client) {
        return new GitIntegrationService(
                repo, secretBox, mapper, new GitProviderFactory(List.of(client), List.of(), List.of()));
    }

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
        assertThrows(TessaryException.class, () -> service.connect(pid, github()));
    }

    @Test
    void require_throwsWhenUnbound() {
        String pid = TenantFixture.bootstrap(tenants, "git-none").project().id();
        assertThrows(TessaryException.class, () -> service.require(pid));
    }

    @Test
    void delete_removesIntegration() {
        String pid = TenantFixture.bootstrap(tenants, "git-del").project().id();
        service.connect(pid, github());
        assertTrue(service.delete(pid));
        assertTrue(service.find(pid).isEmpty());
    }

    // ---- connectVerified: nothing is stored unless the provider confirms the read -------------

    /** A provider that answers verifyAccess and refuses everything else. */
    private record StubClient(GitProviderClient.RepoAccess access, RuntimeException failure)
            implements GitProviderClient {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public GitProviderClient.RepoAccess verifyAccess(GitIntegrationRow integ) {
            if (failure != null) throw failure;
            return access;
        }

        @Override
        public String resolveHeadSha(GitIntegrationRow integ, String branch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommitComparison compare(GitIntegrationRow integ, String baseSha, String headSha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAncestor(GitIntegrationRow integ, String ancestorSha, String descendantSha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepoFile> getTreeFiles(GitIntegrationRow integ, String sha, String pathPrefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChangeRequest openChangeRequest(GitIntegrationRow integ, ChangeRequestSpec spec) {
            throw new UnsupportedOperationException();
        }
    }

    private static ConnectRequest pat(String branch) {
        return new ConnectRequest("github", "acme", "web", null, branch, null, "github_pat_example");
    }

    @Test
    void connectVerified_takesTheDefaultBranchFromTheProviderWhenNoneWasGiven() {
        String pid =
                TenantFixture.bootstrap(tenants, "git-verify-branch").project().id();
        GitIntegrationService svc = withProvider(new StubClient(new GitProviderClient.RepoAccess("trunk"), null));

        GitIntegrationRow row = svc.connectVerified(pid, pat(null));

        // "main" was a guess. The repo's real default branch is what RCA has to check out.
        assertEquals("trunk", row.defaultBranch());
        assertEquals("trunk", svc.require(pid).defaultBranch());
    }

    @Test
    void connectVerified_keepsAnExplicitBranchOverTheProviderDefault() {
        String pid = TenantFixture.bootstrap(tenants, "git-verify-explicit")
                .project()
                .id();
        GitIntegrationService svc = withProvider(new StubClient(new GitProviderClient.RepoAccess("trunk"), null));

        assertEquals("release", svc.connectVerified(pid, pat("release")).defaultBranch());
    }

    @Test
    void connectVerified_storesNothingWhenTheProviderRefuses() {
        String pid =
                TenantFixture.bootstrap(tenants, "git-verify-refused").project().id();
        GitIntegrationService svc =
                withProvider(new StubClient(null, new TessaryException(GitError.REPO_UNREACHABLE, "acme/web")));

        TessaryException e = assertThrows(TessaryException.class, () -> svc.connectVerified(pid, pat(null)));
        assertEquals(GitError.REPO_UNREACHABLE, e.error());
        // The whole point: a credential that cannot read the repo must not look connected, because
        // the next thing that notices is an RCA quietly ruling on traces alone.
        assertTrue(svc.find(pid).isEmpty(), "a refused connect leaves the project unbound");
    }

    @Test
    void connect_doesNotAskTheProvider_theInstallCallbackAlreadyProvedTheRepo() {
        String pid =
                TenantFixture.bootstrap(tenants, "git-verify-skip").project().id();
        GitIntegrationService svc =
                withProvider(new StubClient(null, new IllegalStateException("verifyAccess must not be called here")));

        assertEquals("acme", svc.connect(pid, pat("main")).repoOwner());
    }
}

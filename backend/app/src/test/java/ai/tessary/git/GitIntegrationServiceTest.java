// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationDtos.DeleteResponse;
import ai.tessary.git.GitIntegrationDtos.GitIntegrationView;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    @Autowired
    TenantPathResolver resolver;

    @Autowired
    OrgMembershipRepository memberships;

    /** A service whose one provider answers however this test needs it to. */
    private GitIntegrationService withProvider(GitProviderClient client) {
        return new GitIntegrationService(repo, secretBox, mapper, new GitProviderFactory(List.of(client), List.of()));
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
        assertEquals(row.id(), service.find(pid).orElseThrow().id());
    }

    @Test
    void connect_rejectsDuplicateForSameProject() {
        String pid = TenantFixture.bootstrap(tenants, "git-dup").project().id();
        service.connect(pid, github());
        assertThrows(TessaryException.class, () -> service.connect(pid, github()));
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
        assertEquals("trunk", svc.find(pid).orElseThrow().defaultBranch());
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

    // ---- the endpoints and the refusals behind them --------------------------------------------

    private static TenantContext session(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
    }

    @Test
    void controller_anOwnerConnectsReadsAndDisconnects_andASecondDisconnectDeletesNothing() {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "git-ctl-owner");
        GitIntegrationController ctl = new GitIntegrationController(
                withProvider(new StubClient(new GitProviderClient.RepoAccess("trunk"), null)), resolver);
        TenantContext owner = session(fix.user());
        String org = fix.org().slug();
        String proj = fix.project().slug();

        assertNull(ctl.get(owner, org, proj).data(), "nothing is connected yet");
        GitIntegrationView view = ctl.connect(owner, org, proj, pat(null)).data();
        assertEquals(new GitIntegrationView("github", null, "acme", "web", "trunk"), view);
        assertEquals(view, ctl.get(owner, org, proj).data());
        assertEquals(new DeleteResponse(true), ctl.disconnect(owner, org, proj).data());
        assertEquals(new DeleteResponse(false), ctl.disconnect(owner, org, proj).data(), "already gone");
    }

    /** A member can see the binding but not change it: the credential it stores reads the org's code. */
    @Test
    void controller_aMemberCanReadButNotConnectOrDisconnect() {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "git-ctl-member");
        Principal member = tenants.upsertUserFromWorkos(
                "user_git_member_" + System.nanoTime(), "git-member+" + System.nanoTime() + "@example.com", "m", null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), "member", Instant.now().toString()));
        GitIntegrationController ctl = new GitIntegrationController(
                withProvider(new StubClient(new GitProviderClient.RepoAccess("trunk"), null)), resolver);
        TenantContext ctx = session(member);
        String org = fix.org().slug();
        String proj = fix.project().slug();

        assertNull(ctl.get(ctx, org, proj).data());
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> ctl.connect(ctx, org, proj, pat(null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> ctl.disconnect(ctx, org, proj))
                        .getStatusCode());
        assertTrue(service.find(fix.project().id()).isEmpty());
    }

    @Test
    void connect_rejectsAProviderItDoesNotSupport() {
        String pid = TenantFixture.bootstrap(tenants, "git-gitlab").project().id();
        ConnectRequest gitlab = new ConnectRequest("gitlab", "acme", "web", null, "main", null, "glpat_x");

        TessaryException e = assertThrows(TessaryException.class, () -> service.connect(pid, gitlab));
        assertEquals(GitError.UNSUPPORTED_PROVIDER, e.error());
        assertTrue(service.find(pid).isEmpty());
    }

    private GitIntegrationService unkeyed() {
        return new GitIntegrationService(
                repo, new SecretBox(new TessaryProperties()), mapper, new GitProviderFactory(List.of(), List.of()));
    }

    @Test
    void connect_withoutASecretKey_neverStoresATokenInTheClear() {
        String pid = TenantFixture.bootstrap(tenants, "git-nokey").project().id();
        ConnectRequest req = new ConnectRequest("github", "acme", "web", null, "main", null, "ghp_example");

        TessaryException e =
                assertThrows(TessaryException.class, () -> unkeyed().connect(pid, req));
        assertEquals(GitError.SECRET_KEY_MISSING, e.error());
        assertTrue(repo.findByProject(pid).isEmpty());
    }

    /** A blank token is no credential at all: it neither demands a server key nor gets sealed and stored. */
    @Test
    void connect_aBlankTokenStoresNoCredentials() {
        String pid =
                TenantFixture.bootstrap(tenants, "git-blank-token").project().id();
        ConnectRequest req = new ConnectRequest("github", "acme", "web", null, "main", null, "  ");

        unkeyed().connect(pid, req);

        assertNull(repo.findByProject(pid).orElseThrow().credentialsEnc());
    }
}

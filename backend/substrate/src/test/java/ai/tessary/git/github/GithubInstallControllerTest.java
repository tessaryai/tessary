// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProperties;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationDtos.SelectInstallationRequest;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GithubInstallControllerTest {

    private static final String HOST = "api.github.com";

    private GithubAppProperties props;
    private GithubInstallStateService state;
    private GithubTokenService tokenService;
    private GitIntegrationService integrations;
    private TenantPathResolver resolver;
    private AuthProperties workos;
    private GithubInstallController controller;

    private final TenantContext ctx = new TenantContext("u", "e@x.io", "o", "p1", "owner", null);

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 3);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    @BeforeEach
    void setUp() {
        props = new GithubAppProperties();
        props.setAppId("123");
        props.setPrivateKeyPem("-----BEGIN RSA PRIVATE KEY-----\nx\n-----END RSA PRIVATE KEY-----");
        props.setClientId("cid");
        props.setClientSecret("sec");
        props.setAppSlug("tessary-evals");
        state = new GithubInstallStateService(secretBox(), new com.fasterxml.jackson.databind.ObjectMapper());
        tokenService = mock(GithubTokenService.class);
        integrations = mock(GitIntegrationService.class);
        resolver = mock(TenantPathResolver.class);
        workos = new AuthProperties();
        workos.setFrontendUrl("https://app.example.com/");
        controller = new GithubInstallController(props, state, tokenService, integrations, resolver, workos);
        Resolved resolved = new Resolved(
                new Organization("o", "wo", "acme", "Acme", "t", null, null),
                new Project("p1", "o", "web", "Web", "d", "t", null, null, true, null),
                "owner");
        when(resolver.requireProject(ctx, "acme", "web")).thenReturn(resolved);
    }

    private GitIntegrationRow row() {
        return new GitIntegrationRow("i1", "p1", "github", null, "acme", "web", "main", "enc", "t", "t");
    }

    // ---- authorize-url -----------------------------------------------------

    @Test
    void authorizeUrl_returnsOAuthAuthorizeWithClientIdStateAndRedirectUri() {
        String url = controller.authorizeUrl(ctx, "acme", "web").data().url();
        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?client_id=cid"), url);
        assertTrue(url.contains("&state="), url);
        // redirect_uri is pinned to our callback (URL-encoded) so GitHub 302s back deterministically.
        assertTrue(url.contains("&redirect_uri=https%3A%2F%2Fapp.example.com%2Fgit%2Fgithub%2Fcallback"), url);
    }

    @Test
    void authorizeUrl_missingOAuth_throwsMissingConfig() {
        props.setClientId("");
        TessaryException ex = assertThrows(TessaryException.class, () -> controller.authorizeUrl(ctx, "acme", "web"));
        assertEquals(GitError.MISSING_APP_CONFIG, ex.error());
    }

    // ---- select-installation (IDOR-critical validation) --------------------

    @Test
    void select_validPick_connects() {
        String token = state.mintSelection("acme", "web", "p1", List.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(List.of(new InstalledRepo("acme", "web", "main")));
        when(integrations.connect(eq("p1"), any())).thenReturn(row());

        controller.selectInstallation(ctx, "acme", "web", new SelectInstallationRequest(token, 111L, "acme", "web"));

        verify(integrations)
                .connect(
                        eq("p1"),
                        argThat((ConnectRequest req) -> req.installationId() == 111L
                                && req.repoOwner().equals("acme")
                                && req.repoName().equals("web")));
    }

    @Test
    void select_tokenMintedForAnotherProject_forbidden() {
        String foreign = state.mintSelection("acme", "web", "OTHER_PROJECT", List.of(111L));
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> controller.selectInstallation(
                        ctx, "acme", "web", new SelectInstallationRequest(foreign, 111L, "acme", "web")));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void select_installationNotInVerifiedSet_forbidden() {
        String token = state.mintSelection("acme", "web", "p1", List.of(111L));
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> controller.selectInstallation(
                        ctx, "acme", "web", new SelectInstallationRequest(token, 999L, "acme", "web")));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void select_repoNotInInstallation_forbidden() {
        String token = state.mintSelection("acme", "web", "p1", List.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(List.of(new InstalledRepo("acme", "other", "main")));
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> controller.selectInstallation(
                        ctx, "acme", "web", new SelectInstallationRequest(token, 111L, "acme", "web")));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
        verify(integrations, never()).connect(anyString(), any());
    }

    // ---- installation-options ----------------------------------------------

    @Test
    void options_listsReposBehindTheVerifiedInstallations() {
        String token = state.mintSelection("acme", "web", "p1", List.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(List.of(new InstalledRepo("acme", "web", "main"), new InstalledRepo("acme", "api", "dev")));
        var candidates =
                controller.installationOptions(ctx, "acme", "web", token).data().candidates();
        assertEquals(2, candidates.size());
        assertEquals("web", candidates.get(0).name());
        assertEquals(111L, candidates.get(0).installationId());
    }

    @Test
    void options_tokenForAnotherProject_forbidden() {
        String foreign = state.mintSelection("acme", "web", "OTHER_PROJECT", List.of(111L));
        TessaryException ex =
                assertThrows(TessaryException.class, () -> controller.installationOptions(ctx, "acme", "web", foreign));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
    }

    // ---- install-url --------------------------------------------------------

    @Test
    void installUrl_pointsAtTheAppsInstallPageWithStateBoundToThisProject() {
        String url = controller.installUrl(ctx, "acme", "web").data().url();

        String prefix = "https://github.com/apps/tessary-evals/installations/new?state=";
        assertTrue(url.startsWith(prefix), url);
        String s = URLDecoder.decode(url.substring(prefix.length()), StandardCharsets.UTF_8);
        assertEquals("p1", state.verify(s).projectId(), "the callback binds the install to the project that asked");
    }

    /** Without the App key or its slug there is no install page to send anyone to. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "no-app-id"})
    void installUrl_refusesWithoutAConfiguredAppAndSlug(String slug) {
        if ("no-app-id".equals(slug)) {
            props.setAppId("");
        } else {
            props.setAppSlug(slug);
        }
        TessaryException ex = assertThrows(TessaryException.class, () -> controller.installUrl(ctx, "acme", "web"));
        assertEquals(GitError.MISSING_APP_CONFIG, ex.error());
    }

    @Test
    void authorizeUrl_withoutAFrontendBaseLeavesTheRedirectToTheAppsDefault() {
        workos.setFrontendUrl(" ");
        String url = controller.authorizeUrl(ctx, "acme", "web").data().url();
        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?client_id=cid&state="), url);
        assertFalse(url.contains("redirect_uri"), "a blank base would pin the redirect to a relative path: " + url);
    }

    @Test
    void authorizeUrl_withoutTheAppKeyThrowsMissingConfig() {
        props.setPrivateKeyPem("");
        TessaryException ex = assertThrows(TessaryException.class, () -> controller.authorizeUrl(ctx, "acme", "web"));
        assertEquals(GitError.MISSING_APP_CONFIG, ex.error());
    }

    /**
     * Only an owner, or a project-bound MCP token, may connect the App: a plain member of the org would
     * otherwise bind a repository to a project they do not run.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonOwners_areRefusedUnlessTheCallerIsAnMcpToken(boolean mcpToken) {
        TenantContext member = new TenantContext("u2", "m@x.io", "o", "p1", "member", mcpToken ? "tok" : null);
        when(resolver.requireProject(member, "acme", "web"))
                .thenReturn(new Resolved(
                        new Organization("o", "wo", "acme", "Acme", "t", null, null),
                        new Project("p1", "o", "web", "Web", "d", "t", null, null, true, null),
                        "member"));

        if (mcpToken) {
            assertTrue(controller.installUrl(member, "acme", "web").data().url().contains("state="));
        } else {
            ResponseStatusException ex =
                    assertThrows(ResponseStatusException.class, () -> controller.installUrl(member, "acme", "web"));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
    }
}

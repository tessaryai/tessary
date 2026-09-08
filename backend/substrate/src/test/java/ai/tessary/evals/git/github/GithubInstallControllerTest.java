// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import ai.tessary.evals.auth.AuthProperties;
import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.config.EvalsProperties;
import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.evals.git.GitIntegrationDtos.SelectInstallationRequest;
import ai.tessary.evals.git.GitIntegrationRow;
import ai.tessary.evals.git.GitIntegrationService;
import ai.tessary.evals.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import ai.tessary.evals.tenant.Organization;
import ai.tessary.evals.tenant.Project;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubInstallControllerTest {

    private static final String HOST = "api.github.com";

    private GithubAppProperties props;
    private GithubInstallStateService state;
    private GithubTokenService tokenService;
    private GitIntegrationService integrations;
    private TenantPathResolver resolver;
    private GithubInstallController controller;

    private final TenantContext ctx = new TenantContext("u", "e@x.io", "o", "p1", "owner", null);

    private static SecretBox secretBox() {
        EvalsProperties p = new EvalsProperties();
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
        AuthProperties workos = new AuthProperties();
        workos.setFrontendUrl("https://app.example.com/");
        controller = new GithubInstallController(props, state, tokenService, integrations, resolver, workos);
        Resolved resolved = new Resolved(
                new Organization("o", "wo", "acme", "Acme", "t", null, null),
                new Project("p1", "o", "web", "Web", "d", "t", null, null, true, null),
                "owner");
        when(resolver.requireProject(ctx, "acme", "web")).thenReturn(resolved);
    }

    private GitIntegrationRow row() {
        return new GitIntegrationRow("i1", "p1", "github", null, "acme", "web", "main", "enc", null, "t", "t");
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
        EvalsException ex = assertThrows(EvalsException.class, () -> controller.authorizeUrl(ctx, "acme", "web"));
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
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> controller.selectInstallation(
                        ctx, "acme", "web", new SelectInstallationRequest(foreign, 111L, "acme", "web")));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void select_installationNotInVerifiedSet_forbidden() {
        String token = state.mintSelection("acme", "web", "p1", List.of(111L));
        EvalsException ex = assertThrows(
                EvalsException.class,
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
        EvalsException ex = assertThrows(
                EvalsException.class,
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
        EvalsException ex =
                assertThrows(EvalsException.class, () -> controller.installationOptions(ctx, "acme", "web", foreign));
        assertEquals(GitError.INSTALL_FORBIDDEN, ex.error());
    }
}

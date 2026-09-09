// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProperties;
import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.open.errors.TessaryException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GithubCallbackControllerTest {

    private GithubInstallStateService state;
    private GithubTokenService tokenService;
    private GitIntegrationService integrations;
    private GithubCallbackController controller;

    private static final String HOST = "api.github.com";

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 7);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    @BeforeEach
    void setUp() {
        state = new GithubInstallStateService(secretBox(), new com.fasterxml.jackson.databind.ObjectMapper());
        tokenService = mock(GithubTokenService.class);
        integrations = mock(GitIntegrationService.class);
        AuthProperties workos = new AuthProperties();
        workos.setFrontendUrl("https://app.example.com/");
        controller = new GithubCallbackController(state, tokenService, integrations, workos);
        when(integrations.find(anyString())).thenReturn(Optional.empty());
    }

    private String stateFor(String projectId) {
        return state.mint("acme", "web", projectId);
    }

    private static String location(ResponseEntity<Void> resp) {
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        return resp.getHeaders().getLocation().toString();
    }

    // ---- idempotency -------------------------------------------------------

    @Test
    void alreadyBound_bouncesConnected_withoutTouchingGithub() {
        GitIntegrationRow existing =
                new GitIntegrationRow("i1", "p1", "github", null, "acme", "web", "main", "enc", null, "t", "t");
        when(integrations.find("p1")).thenReturn(Optional.of(existing));
        ResponseEntity<Void> resp = controller.callback(999L, stateFor("p1"), "code", "install");
        assertTrue(location(resp).endsWith("/orgs/acme/projects/web/settings/git?connected=1"));
        verify(integrations, never()).connect(anyString(), any());
    }

    // ---- fresh install (installation_id present) ---------------------------

    @Test
    void freshInstall_singleRepo_connectsAndBouncesSetup() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(List.of(new InstalledRepo("acme", "web", "main")));
        ResponseEntity<Void> resp = controller.callback(111L, stateFor("p1"), "code", "install");
        assertTrue(location(resp).contains("/settings/git?connected=1"));
        verify(integrations).connect(eq("p1"), any(ConnectRequest.class));
    }

    @Test
    void freshInstall_missingCode_failsClosed_redirectingWithError() {
        // Fail-closed on the binding, friendly on the browser: no connect happens, and the user
        // lands back on the Generate step with the error code instead of a raw JSON body.
        ResponseEntity<Void> resp = controller.callback(111L, stateFor("p1"), null, "install");
        assertTrue(location(resp).contains("/settings/git?github_error=GIT.INSTALL_NOT_VERIFIED"));
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void freshInstall_userDoesNotAdminister_forbidden_redirectingWithError() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of(222L)); // not 111
        ResponseEntity<Void> resp = controller.callback(111L, stateFor("p1"), "code", "install");
        assertTrue(location(resp).contains("/settings/git?github_error=GIT.INSTALL_FORBIDDEN"));
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void freshInstall_multipleRepos_redirectsToPicker() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(
                        List.of(new InstalledRepo("acme", "web", "main"), new InstalledRepo("acme", "api", "main")));
        ResponseEntity<Void> resp = controller.callback(111L, stateFor("p1"), "code", "install");
        assertTrue(location(resp).contains("/settings/git?install_select="));
        verify(integrations, never()).connect(anyString(), any());
    }

    // ---- invalid state: no verified project, nothing to redirect to ---------

    @Test
    void invalidState_stillThrows() {
        assertThrows(TessaryException.class, () -> controller.callback(111L, "garbage-state", "code", "install"));
        verify(integrations, never()).connect(anyString(), any());
    }

    // ---- reuse (no installation_id) ----------------------------------------

    @Test
    void reuse_missingCode_failsClosed_redirectingWithError() {
        ResponseEntity<Void> resp = controller.callback(null, stateFor("p1"), null, null);
        assertTrue(location(resp).contains("/settings/git?github_error=GIT.INSTALL_NOT_VERIFIED"));
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void reuse_noAdministeredInstallations_redirectsWithNoAdminError() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of());
        ResponseEntity<Void> resp = controller.callback(null, stateFor("p1"), "code", null);
        assertTrue(location(resp).contains("/settings/git?github_error=GIT.NO_ADMIN_INSTALLATIONS"));
        verify(integrations, never()).connect(anyString(), any());
    }

    @Test
    void reuse_singleInstallationSingleRepo_connects() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of(111L));
        when(tokenService.listInstallationRepos(111L, HOST))
                .thenReturn(List.of(new InstalledRepo("acme", "web", "main")));
        ResponseEntity<Void> resp = controller.callback(null, stateFor("p1"), "code", null);
        assertTrue(location(resp).contains("/settings/git?connected=1"));
        verify(integrations).connect(eq("p1"), any(ConnectRequest.class));
    }

    @Test
    void reuse_multipleInstallations_redirectsToPickerWithoutEnumerating() {
        when(tokenService.exchangeUserCode("code")).thenReturn("utok");
        when(tokenService.listUserInstallations("utok")).thenReturn(Set.of(111L, 222L));
        ResponseEntity<Void> resp = controller.callback(null, stateFor("p1"), "code", null);
        assertTrue(location(resp).contains("/settings/git?install_select="));
        verify(integrations, never()).connect(anyString(), any());
        // Multiple installations short-circuit straight to the picker — no per-installation repo listing here.
        verify(tokenService, never()).listInstallationRepos(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static ai.tessary.git.github.GithubFixtures.location;
import static ai.tessary.git.github.GithubFixtures.secretBox;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProperties;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

class GithubCallbackControllerTest {

    private GithubInstallStateService state;
    private GithubTokenService tokenService;
    private GitIntegrationService integrations;
    private GithubCallbackController controller;

    private static final String HOST = "api.github.com";

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

    // ---- idempotency -------------------------------------------------------

    @Test
    void alreadyBound_bouncesConnected_withoutTouchingGithub() {
        GitIntegrationRow existing =
                new GitIntegrationRow("i1", "p1", "github", null, "acme", "web", "main", "enc", "t", "t");
        when(integrations.find("p1")).thenReturn(Optional.of(existing));
        ResponseEntity<Void> resp = controller.callback(999L, stateFor("p1"), "code", "install");
        assertTrue(location(resp).endsWith("/orgs/acme/projects/web/settings/git?connected=1"));
        verify(integrations, never()).connect(anyString(), any());
    }

    /**
     * Each row: the installation id (none on a reuse), the code, the installations the user administers, the repos
     * installation 111 grants (a dash leaves either unstubbed), where the browser lands, whether the project is bound,
     * and the bug it catches.
     */
    @ParameterizedTest(name = "{6}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
        111 | -    | -    | -       | github_error=GIT.INSTALL_NOT_VERIFIED  | false | a fresh install without a code is bound unverified
        111 | code | 222  | -       | github_error=GIT.INSTALL_FORBIDDEN    | false | an installation the user does not administer is bound
        111 | code | 111  | web api | install_select=                       | false | a fresh install granting two repos binds one without asking
        -   | -    | -    | -       | github_error=GIT.INSTALL_NOT_VERIFIED | false | a reuse without a code is bound unverified
        -   | code | none | -       | github_error=GIT.NO_ADMIN_INSTALLATIONS | false | a user administering no installation gets no error
        -   | code | 111  | web     | connected=1                           | true  | a reuse with one installation and one repo is not bound
        111 | code | 111  | none    | github_error=GIT.NO_INSTALLED_REPOS   | false | an install granting no repos binds an empty integration
        """)
    void theCallbackRedirects(
            @Nullable Long installationId,
            @Nullable String code,
            @Nullable String administered,
            @Nullable String repos,
            String redirect,
            boolean connects,
            String bug) {
        if (administered != null) {
            when(tokenService.exchangeUserCode("code")).thenReturn("utok");
            when(tokenService.listUserInstallations("utok"))
                    .thenReturn(administered.equals("none") ? Set.of() : Set.of(Long.parseLong(administered)));
        }
        if (repos != null) {
            when(tokenService.listInstallationRepos(111L, HOST))
                    .thenReturn(
                            repos.equals("none")
                                    ? List.of()
                                    : Arrays.stream(repos.split(" "))
                                            .map(name -> new InstalledRepo("acme", name, "main"))
                                            .toList());
        }

        ResponseEntity<Void> resp =
                controller.callback(installationId, stateFor("p1"), code, installationId == null ? null : "install");

        assertTrue(location(resp).contains("/settings/git?" + redirect), bug + ": " + location(resp));
        if (connects) {
            verify(integrations).connect(eq("p1"), any(ConnectRequest.class));
        } else {
            verify(integrations, never()).connect(anyString(), any());
        }
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

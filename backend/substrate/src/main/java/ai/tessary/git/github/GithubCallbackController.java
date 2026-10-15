// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.auth.AuthProperties;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.github.GithubInstallStateService.StatePayload;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outside {@code /api} and allowlisted in {@code AuthFilter}; the signed state is the only credential. IDOR defense:
 * {@code installation_id} is attacker-controllable, so the installer's OAuth {@code code} is exchanged and the
 * installation must be one that user administers, failing closed. A reuse arrival (App already installed) has a code
 * but no installation id, and enumerates the user's administered installations.
 */
@RestController
public class GithubCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GithubCallbackController.class);

    private final GithubInstallStateService state;
    private final GithubTokenService tokenService;
    private final GitIntegrationService integrations;
    private final AuthProperties workos;

    public GithubCallbackController(
            GithubInstallStateService state,
            GithubTokenService tokenService,
            GitIntegrationService integrations,
            AuthProperties workos) {
        this.state = state;
        this.tokenService = tokenService;
        this.integrations = integrations;
        this.workos = workos;
    }

    private static final String GITHUB_HOST = GithubAppProperties.DEFAULT_API_HOST;

    @GetMapping("/git/github/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "installation_id", required = false) Long installationId,
            @RequestParam("state") String stateParam,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "setup_action", required = false) String setupAction) {
        StatePayload p = state.verify(stateParam);

        // A failure must land back on the project's page with ?github_error=, never a raw JSON body. An invalid state
        // still throws: with no verified project there is nowhere safe to redirect.
        try {
            // Idempotent; verification only gates a fresh connect.
            if (integrations.find(p.projectId()).isPresent()) {
                return redirect(setupConnected(p));
            }

            if (installationId != null) {
                verifyInstallerAdministersInstallation(code, installationId);
                return bindOrPick(p, List.of(installationId));
            }

            // The installations the code's user administers are the verified set.
            if (code == null || code.isBlank()) {
                throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
            }
            String userToken = tokenService.exchangeUserCode(code);
            List<Long> administered = List.copyOf(tokenService.listUserInstallations(userToken));
            if (administered.isEmpty()) {
                throw new TessaryException(GitError.NO_ADMIN_INSTALLATIONS);
            }
            return bindOrPick(p, administered);
        } catch (TessaryException e) {
            log.warn("github callback failed for project {}: {}", p.projectId(), e.getMessage());
            return redirect(setupError(p, e.error().code()));
        }
    }

    /** The picker's sealed state carries the verified ids, so the later pick stays bounded to that set. */
    private ResponseEntity<Void> bindOrPick(StatePayload p, List<Long> installationIds) {
        if (installationIds.size() == 1) {
            // Correctness hinges on GET /user/installations returning only installations the user administers; do not
            // widen that set.
            long only = installationIds.get(0);
            List<InstalledRepo> repos = tokenService.listInstallationRepos(only, GITHUB_HOST);
            if (repos.isEmpty()) {
                throw new TessaryException(GitError.NO_INSTALLED_REPOS);
            }
            if (repos.size() == 1) {
                InstalledRepo repo = repos.get(0);
                integrations.connect(
                        p.projectId(),
                        new ConnectRequest(
                                "github", repo.owner(), repo.name(), null, repo.defaultBranch(), only, null));
                log.info("github connected for project {} -> {}/{}", p.projectId(), repo.owner(), repo.name());
                return redirect(setupConnected(p));
            }
        }
        String sel = state.mintSelection(p.orgSlug(), p.projectSlug(), p.projectId(), installationIds);
        return redirect(setupSelect(p, sel));
    }

    /** Fails closed: a missing code, missing OAuth config or a non-administered installation all reject. */
    private void verifyInstallerAdministersInstallation(String code, long installationId) {
        if (code == null || code.isBlank()) {
            throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
        }
        String userToken = tokenService.exchangeUserCode(code);
        if (!tokenService.listUserInstallations(userToken).contains(installationId)) {
            log.warn(
                    "github install rejected: installation {} not administered by the authorizing user",
                    installationId);
            throw new TessaryException(GitError.INSTALL_FORBIDDEN);
        }
    }

    // Built from the deployment-wide frontend base plus the project from state, so one config value serves every
    // project.
    private String setupConnected(StatePayload p) {
        return settingsGit(p) + "?connected=1";
    }

    private String setupError(StatePayload p, String errorCode) {
        return settingsGit(p) + "?github_error=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
    }

    // The page is ungated, so the picker is not bounced away before the token is used.
    private String setupSelect(StatePayload p, String selToken) {
        return settingsGit(p) + "?install_select=" + URLEncoder.encode(selToken, StandardCharsets.UTF_8);
    }

    private String settingsGit(StatePayload p) {
        return frontendBase() + "/orgs/" + p.orgSlug() + "/projects/" + p.projectSlug() + "/settings/git";
    }

    private String frontendBase() {
        String base = workos.getFrontendUrl();
        return (base == null || base.isBlank()) ? "" : base.replaceAll("/+$", "");
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }
}

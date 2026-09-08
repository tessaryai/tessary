// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import ai.tessary.evals.auth.AuthProperties;
import ai.tessary.evals.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.evals.git.GitIntegrationService;
import ai.tessary.evals.git.github.GithubInstallStateService.StatePayload;
import ai.tessary.evals.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
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
 * The GitHub App post-install redirect target. GitHub sends the browser here with
 * {@code installation_id} + the signed {@code state}. This endpoint is OUTSIDE {@code
 * /api} (no cookie session) and is allowlisted in {@code AuthFilter}; the signed state is
 * the only credential. It verifies the state, resolves the installed repo, binds the
 * project, and 302-redirects back to the SPA with {@code ?connected=1}.
 *
 * <p>Two arrival shapes share this one endpoint (it is the App's configured callback for both):
 * <ul>
 *   <li><b>Fresh install</b> — GitHub delivers {@code installation_id} + an OAuth {@code code}
 *       together (the "request user authorization during installation" flow). First install on the
 *       account.
 *   <li><b>Reuse an existing installation</b> — a GitHub App installs only ONCE per account, so the
 *       2nd+ project can't re-install. The frontend instead sends the user through a standalone OAuth
 *       authorize ({@link GithubInstallController#authorizeUrl}); GitHub returns a {@code code} with
 *       <b>no</b> {@code installation_id}. We identify the user, enumerate the installations they
 *       administer, and bind one.
 * </ul>
 *
 * <p>Both shapes funnel through {@link #bindOrPick}: a single installation granting a single repo
 * auto-connects; anything ambiguous (multiple installations, or one installation with multiple
 * repos) redirects to the SPA repo picker carrying a fresh sealed {@code state}, finalized by
 * {@link GithubInstallController#selectInstallation}.
 *
 * <p>The redirect target is built per-install from the signed {@code state} (which carries
 * the org/project that initiated the install), so a single deployment-wide frontend base
 * URL ({@code EVALS_AUTH_FRONTEND_URL}) serves every org and project — there is no per-project
 * redirect config.
 *
 * <p><b>IDOR defense:</b> the {@code installation_id} query param is attacker-controllable, so
 * binding it on trust would let a project owner attach a victim's installation and clone their
 * private repos. We therefore require GitHub's "authorize user during installation" {@code code},
 * exchange it for the installer's user token, and confirm {@code installation_id} is one that user
 * actually administers ({@code GET /user/installations}) before connecting. Missing/failing
 * verification fails CLOSED.
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

        // From here on we know which project initiated the connect, so a failure must land the
        // user back on that project's Setup page with ?github_error=<code> (toast + retry) — a
        // browser navigation must never dead-end on a raw JSON error body. An invalid state above
        // still throws: with no verified project there is nowhere safe to redirect.
        try {
            // Idempotent: re-arriving for an already-bound project just bounces back connected (no new
            // binding, so no IDOR surface — verification only gates a fresh connect).
            if (integrations.find(p.projectId()).isPresent()) {
                return redirect(setupConnected(p));
            }

            if (installationId != null) {
                // Fresh-install arrival: GitHub delivered installation_id alongside the OAuth code.
                verifyInstallerAdministersInstallation(code, installationId);
                return bindOrPick(p, List.of(installationId));
            }

            // Reuse arrival: standalone OAuth authorize (App already installed) → no installation_id.
            // The code identifies the user; the installations they administer ARE the verified set
            // (same proof as the fresh-install IDOR check, just enumerated rather than spot-checked).
            if (code == null || code.isBlank()) {
                throw new EvalsException(GitError.INSTALL_NOT_VERIFIED);
            }
            String userToken = tokenService.exchangeUserCode(code);
            List<Long> administered = List.copyOf(tokenService.listUserInstallations(userToken));
            if (administered.isEmpty()) {
                throw new EvalsException(GitError.NO_ADMIN_INSTALLATIONS);
            }
            return bindOrPick(p, administered);
        } catch (EvalsException e) {
            log.warn("github callback failed for project {}: {}", p.projectId(), e.getMessage());
            return redirect(setupError(p, e.error().code()));
        }
    }

    /**
     * Bind the one unambiguous repo, or redirect to the picker. The {@code installationIds} have
     * already been proven user-administered by the caller; the picker's sealed state carries them so
     * the user's later pick stays bounded to that verified set (IDOR defense survives the round-trip).
     * Enumerating repos is deferred to the single-installation case so a user with many installations
     * doesn't pay for listing every one's repos here — the picker lists them lazily.
     */
    private ResponseEntity<Void> bindOrPick(StatePayload p, List<Long> installationIds) {
        if (installationIds.size() == 1) {
            // Reuse path correctness hinges on GET /user/installations (via listUserInstallations)
            // returning ONLY installations the authenticated user administers — do not widen that set.
            long only = installationIds.get(0);
            List<InstalledRepo> repos = tokenService.listInstallationRepos(only, GITHUB_HOST);
            if (repos.isEmpty()) {
                throw new EvalsException(GitError.NO_INSTALLED_REPOS);
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
        // Ambiguous (multiple installations, or one installation with multiple repos) → let the user
        // pick in the SPA, finalized by GithubInstallController.selectInstallation against this token.
        String sel = state.mintSelection(p.orgSlug(), p.projectSlug(), p.projectId(), installationIds);
        return redirect(setupSelect(p, sel));
    }

    /**
     * Prove the user who just completed the install actually administers this installation before
     * binding it — closing the cross-tenant IDOR on the attacker-controllable {@code installation_id}.
     * Fails CLOSED: a missing {@code code} (OAuth-during-install not enabled), missing OAuth config,
     * or an installation the authorizing user can't administer all reject.
     */
    private void verifyInstallerAdministersInstallation(String code, long installationId) {
        if (code == null || code.isBlank()) {
            throw new EvalsException(GitError.INSTALL_NOT_VERIFIED);
        }
        String userToken = tokenService.exchangeUserCode(code);
        if (!tokenService.listUserInstallations(userToken).contains(installationId)) {
            log.warn(
                    "github install rejected: installation {} not administered by the authorizing user",
                    installationId);
            throw new EvalsException(GitError.INSTALL_FORBIDDEN);
        }
    }

    // Land on Settings -> Git integration (it handles ?connected=1: toast + refresh). Built from the
    // deployment-wide frontend base + the project from state, so one config value serves every project.
    // These three used to land on the project's Setup page; that page is gone, and Git integration is
    // now the only surface that binds a repository, so it owns every callback query param it produced.
    private String setupConnected(StatePayload p) {
        return settingsGit(p) + "?connected=1";
    }

    // The failure counterpart: back to Git integration with the error code for the SPA to toast.
    private String setupError(StatePayload p, String errorCode) {
        return settingsGit(p) + "?github_error=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
    }

    // Land on Git integration with the sealed selection token; it renders the repo picker. The page is
    // ungated (unlike Observer, which onboarding hides until a fresh project graduates — the gate would
    // bounce the picker away and drop the token before the pick could complete).
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.auth.AuthProperties;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitIntegrationDtos.GitIntegrationView;
import ai.tessary.git.GitIntegrationDtos.InstallUrlView;
import ai.tessary.git.GitIntegrationDtos.InstallationOptionsView;
import ai.tessary.git.GitIntegrationDtos.RepoOption;
import ai.tessary.git.GitIntegrationDtos.SelectInstallationRequest;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.github.GithubInstallStateService.SelectionPayload;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GitHub App connect surface for a project. Authenticated + owner-gated — we must know which
 * project is connecting to bind the signed {@code state}. Three ways in, all landing back on
 * {@link GithubCallbackController} or its picker:
 *
 * <ul>
 *   <li>{@code GET /install-url} — first install on an account: {@code github.com/apps/<slug>/installations/new}.
 *   <li>{@code GET /authorize-url} — the App is already installed (a 2nd+ project): a standalone OAuth
 *       authorize that reuses the existing installation instead of trying to re-install (which GitHub
 *       won't do — one installation per account).
 *   <li>{@code GET /installation-options} + {@code POST /select-installation} — the repo picker, used
 *       when an authorize/install resolves more than one candidate repo.
 * </ul>
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/git/github")
public class GithubInstallController {

    private static final String GITHUB_HOST = GithubAppProperties.DEFAULT_API_HOST;

    private final GithubAppProperties props;
    private final GithubInstallStateService state;
    private final GithubTokenService tokenService;
    private final GitIntegrationService integrations;
    private final TenantPathResolver resolver;
    private final AuthProperties workos;

    public GithubInstallController(
            GithubAppProperties props,
            GithubInstallStateService state,
            GithubTokenService tokenService,
            GitIntegrationService integrations,
            TenantPathResolver resolver,
            AuthProperties workos) {
        this.props = props;
        this.state = state;
        this.tokenService = tokenService;
        this.integrations = integrations;
        this.resolver = resolver;
        this.workos = workos;
    }

    /** Fresh-install URL: the browser installs the App, GitHub returns to the callback with installation_id + code. */
    @GetMapping("/install-url")
    public ApiResponse<InstallUrlView> installUrl(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        if (!props.isConfigured()
                || props.getAppSlug() == null
                || props.getAppSlug().isBlank()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, "github");
        }
        String s = state.mint(orgSlug, projectSlug, r.project().id());
        String url = "https://github.com/apps/" + props.getAppSlug() + "/installations/new?state="
                + URLEncoder.encode(s, StandardCharsets.UTF_8);
        return ApiResponse.ok(new InstallUrlView(url));
    }

    /**
     * Standalone OAuth authorize URL: reuses an installation already on the user's account/org (the
     * 2nd+ project case — GitHub installs an App only once per account, so {@code installations/new}
     * just bounces to "configure" and never re-fires our callback). GitHub returns to the callback
     * with a {@code code} and NO installation_id, where we enumerate the user's installations.
     */
    @GetMapping("/authorize-url")
    public ApiResponse<InstallUrlView> authorizeUrl(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        // Both the app key (to mint installation tokens after) and OAuth creds (to identify the user) are required.
        if (!props.isConfigured() || !props.hasOAuth()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, "github");
        }
        String s = state.mint(orgSlug, projectSlug, r.project().id());
        StringBuilder url = new StringBuilder("https://github.com/login/oauth/authorize?client_id=")
                .append(URLEncoder.encode(props.getClientId(), StandardCharsets.UTF_8))
                .append("&state=")
                .append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        // Pin the redirect explicitly to our callback. Without redirect_uri GitHub leaves the user on its
        // "you are being redirected to the authorized application" interstitial (relying on the App's
        // default callback, which may not auto-forward); WITH it GitHub 302s straight back to the callback
        // carrying ?code&state. Built from the same browser-facing base the callback uses to bounce users
        // home, so its host matches the App's registered Callback URL on a single-origin (Caddy) deploy.
        String base = frontendBase();
        if (!base.isEmpty()) {
            url.append("&redirect_uri=")
                    .append(URLEncoder.encode(base + "/git/github/callback", StandardCharsets.UTF_8));
        }
        return ApiResponse.ok(new InstallUrlView(url.toString()));
    }

    private String frontendBase() {
        String base = workos.getFrontendUrl();
        return (base == null || base.isBlank()) ? "" : base.replaceAll("/+$", "");
    }

    /**
     * The repos the user can bind, for the picker. Reads the sealed selection token the callback
     * minted (carrying the verified-administered installation ids) and lists each installation's
     * repos via the App token. The token is bound to this project — a token minted for another
     * project is rejected, so the picker can't be driven cross-tenant.
     */
    @GetMapping("/installation-options")
    public ApiResponse<InstallationOptionsView> installationOptions(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam("token") String token) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        SelectionPayload sel = requireSelectionFor(token, r);
        List<RepoOption> candidates = new ArrayList<>();
        for (long installationId : sel.installationIds()) {
            for (InstalledRepo repo : tokenService.listInstallationRepos(installationId, GITHUB_HOST)) {
                candidates.add(new RepoOption(installationId, repo.owner(), repo.name(), repo.defaultBranch()));
            }
        }
        return ApiResponse.ok(new InstallationOptionsView(candidates));
    }

    /**
     * Finalize a picker choice. Re-validates the pick against the sealed token: the installation must
     * be one the user was proven to administer, and the repo must actually be in that installation
     * (checked via the App token, not trusted from the request) — so neither field can be forged to
     * attach an installation/repo the user has no claim to.
     */
    @PostMapping("/select-installation")
    public ApiResponse<GitIntegrationView> selectInstallation(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody SelectInstallationRequest req) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        SelectionPayload sel = requireSelectionFor(req.token(), r);
        if (!sel.installationIds().contains(req.installationId())) {
            throw new TessaryException(GitError.INSTALL_FORBIDDEN);
        }
        InstalledRepo repo = tokenService.listInstallationRepos(req.installationId(), GITHUB_HOST).stream()
                .filter(rp -> rp.owner().equals(req.repoOwner()) && rp.name().equals(req.repoName()))
                .findFirst()
                .orElseThrow(() -> new TessaryException(GitError.INSTALL_FORBIDDEN));
        var row = integrations.connect(
                r.project().id(),
                new ConnectRequest(
                        "github", repo.owner(), repo.name(), null, repo.defaultBranch(), req.installationId(), null));
        return ApiResponse.ok(GitIntegrationView.from(row));
    }

    /** Open the selection token and bind it to the caller's project (reject cross-project tokens). */
    private SelectionPayload requireSelectionFor(String token, Resolved r) {
        SelectionPayload sel = state.verifySelection(token);
        if (!sel.projectId().equals(r.project().id())) {
            throw new TessaryException(GitError.INSTALL_FORBIDDEN);
        }
        return sel;
    }

    private Resolved requireOwner(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        if (!r.isOwner() && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to manage the GitHub App");
        }
        return r;
    }
}

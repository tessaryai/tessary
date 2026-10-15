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
 * GitHub App connect surface; owner-gated because the signed {@code state} must bind a project. Fresh install, a
 * standalone OAuth authorize for a 2nd+ project (GitHub installs an App once per account), and a repo picker when more
 * than one candidate resolves.
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
     * {@code installations/new} would bounce to "configure" and never re-fire our callback for an existing install.
     * GitHub returns a {@code code} and no installation_id.
     */
    @GetMapping("/authorize-url")
    public ApiResponse<InstallUrlView> authorizeUrl(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        // The app key mints installation tokens; OAuth creds identify the user.
        if (!props.isConfigured() || !props.hasOAuth()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, "github");
        }
        String s = state.mint(orgSlug, projectSlug, r.project().id());
        StringBuilder url = new StringBuilder("https://github.com/login/oauth/authorize?client_id=")
                .append(URLEncoder.encode(props.getClientId(), StandardCharsets.UTF_8))
                .append("&state=")
                .append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        // Without redirect_uri GitHub leaves the user on its interstitial; with it GitHub 302s straight back. Same base
        // as the callback so the host matches the registered Callback URL.
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

    /** The sealed selection token is bound to this project, so the picker can't be driven cross-tenant. */
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

    /** Re-validated against the sealed token and the App token, so neither field can be forged. */
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

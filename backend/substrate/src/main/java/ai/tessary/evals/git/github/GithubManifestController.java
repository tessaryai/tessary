// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import ai.tessary.evals.auth.AuthProperties;
import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.git.GitIntegrationDtos.ManifestStartView;
import ai.tessary.evals.git.github.GithubInstallStateService.StatePayload;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import ai.tessary.evals.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The BYO GitHub App setup wizard (#860): a self-hoster registers their OWN App via GitHub's
 * manifest flow instead of Tessary handing them one, closing the last Tessary-owned-cloud-credential
 * gap in {@code substrate} for Epic 2. Two endpoints:
 *
 * <ul>
 *   <li>{@code GET .../git/github/manifest-url} (authenticated, owner-gated, under {@code /api}) —
 *       builds the App manifest + a signed state, for the frontend to auto-POST as a
 *       {@code manifest} form field to {@code github.com/settings/apps/new}.
 *   <li>{@code GET /git/github/manifest/callback} (unauthenticated, outside {@code /api}, mirroring
 *       {@link GithubCallbackController}) — GitHub lands the browser here with a one-shot {@code code};
 *       exchanged via {@code POST /app-manifests/{code}/conversions} for the new App's own credentials,
 *       persisted by {@link GithubAppConfigService}, then redirected back to Setup.
 * </ul>
 *
 * <p>This mints a deployment-wide (singleton) App, not a per-project one — see
 * {@code 0015-github-app-config.sql}'s header for why. The {@code orgSlug}/{@code projectSlug} in the
 * URL are only where the wizard was launched from and where it redirects back to; they say nothing
 * about which projects the resulting App can be used by (all of them, same as env-configured App
 * credentials always have).
 *
 * <p>The outbound conversion call is behind {@link GithubManifestExchange} — a one-method seam
 * so this controller's redirect/error logic is testable without an HTTP call to {@code
 * api.github.com} (which {@code UrlGuard} would refuse to aim at a local test server anyway).
 */
@RestController
public class GithubManifestController {

    private static final Logger log = LoggerFactory.getLogger(GithubManifestController.class);

    private final GithubInstallStateService state;
    private final GithubAppConfigService appConfig;
    private final TenantPathResolver resolver;
    private final AuthProperties workos;
    private final ObjectMapper mapper;
    private final GithubManifestExchange exchange;

    public GithubManifestController(
            GithubInstallStateService state,
            GithubAppConfigService appConfig,
            TenantPathResolver resolver,
            AuthProperties workos,
            ObjectMapper mapper,
            GithubManifestExchange exchange) {
        this.state = state;
        this.appConfig = appConfig;
        this.resolver = resolver;
        this.workos = workos;
        this.mapper = mapper;
        this.exchange = exchange;
    }

    @GetMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/git/github/manifest-url")
    public ApiResponse<ManifestStartView> manifestUrl(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        // github_app_config is a deployment-wide singleton, not scoped to this org/project (see class
        // javadoc): fail fast here, before minting state or sending the browser to GitHub, rather than
        // letting any owner of ANY org overwrite an App every other org on this deployment already
        // depends on. requireOwner() above only proves ownership of the CALLING org — it says nothing
        // about whether an App is already configured, which is the actual gate this wizard needs.
        if (appConfig.isAppConfigured()) {
            throw new EvalsException(GitError.APP_ALREADY_CONFIGURED);
        }
        String s = state.mint(orgSlug, projectSlug, r.project().id());
        String base = frontendBase();

        // hook_attributes.active = false: the platform has no push-webhook endpoint any more. It had
        // one — GitWebhookController — and Track A removed it with the observer, so an App minted with
        // a live hook would tell GitHub to POST every push at a URL that 404s. Declaring the hook
        // inactive says the same thing to GitHub honestly, and leaves the block in place for whoever
        // brings a push-driven feature back. The `url` key is omitted rather than pointed at a dead
        // path, so nothing has to be un-lied-about later.
        Map<String, Object> hookAttributes = new LinkedHashMap<>();
        hookAttributes.put("active", false);

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("contents", "read");
        permissions.put("metadata", "read");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", "Tessary (" + orgSlug + ")");
        manifest.put("url", base);
        manifest.put("hook_attributes", hookAttributes);
        manifest.put("redirect_url", base + "/git/github/manifest/callback");
        manifest.put("callback_urls", java.util.List.of(base + "/git/github/callback"));
        manifest.put("default_permissions", permissions);
        manifest.put("default_events", java.util.List.of("push"));
        manifest.put("request_oauth_on_install", true);
        manifest.put("public", false);

        String manifestJson;
        try {
            manifestJson = mapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new EvalsException(GitError.MISSING_APP_CONFIG, e, "github");
        }
        String url = "https://github.com/settings/apps/new?state=" + URLEncoder.encode(s, StandardCharsets.UTF_8);
        return ApiResponse.ok(new ManifestStartView(url, manifestJson));
    }

    /**
     * GitHub's manifest-flow redirect target. Outside {@code /api}, allowlisted in {@code AuthFilter}
     * next to {@code /git/github/callback} — the signed {@code state} IS the credential, same
     * reasoning as that endpoint. The manifest {@code code} GitHub hands back is one-shot: a
     * resubmitted callback (double-click, back-button) fails the SECOND conversion call with GitHub's
     * own rejection, surfaced here as {@link GitError#MANIFEST_CONVERSION_FAILED} rather than a
     * generic app-not-configured error.
     */
    @GetMapping("/git/github/manifest/callback")
    public ResponseEntity<Void> callback(
            @RequestParam("state") String stateParam, @RequestParam(value = "code", required = false) String code) {
        StatePayload p = state.verify(stateParam);
        try {
            if (code == null || code.isBlank()) {
                throw new EvalsException(GitError.MANIFEST_CONVERSION_FAILED);
            }
            JsonNode app = exchange.convert(code);
            appConfig.persist(
                    app.path("id").asText(),
                    app.path("pem").asText(),
                    app.path("webhook_secret").asText(null),
                    app.path("slug").asText(),
                    app.path("client_id").asText(),
                    app.path("client_secret").asText());
            log.info("github app config captured for org={} project={}", p.orgSlug(), p.projectSlug());
            return redirect(setupConnected(p));
        } catch (EvalsException e) {
            log.warn("github manifest conversion failed for project {}: {}", p.projectId(), e.getMessage());
            return redirect(setupError(p, e.error().code()));
        }
    }

    // Settings -> Git integration, not the retired Setup page: it already handles both of these
    // params, and it is the surface a freshly-registered App has to be installed from next.
    private String setupConnected(StatePayload p) {
        return settingsGit(p) + "?github_app_connected=1";
    }

    private String setupError(StatePayload p, String errorCode) {
        return settingsGit(p) + "?github_error=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
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

    private Resolved requireOwner(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        if (!r.isOwner() && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to set up a GitHub App");
        }
        return r;
    }
}

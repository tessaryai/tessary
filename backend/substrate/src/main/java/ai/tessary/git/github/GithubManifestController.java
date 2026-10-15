// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.auth.AuthProperties;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.git.GitIntegrationDtos.ManifestStartView;
import ai.tessary.git.github.GithubInstallStateService.StatePayload;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.web.ApiResponse;
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
 * The BYO GitHub App wizard over GitHub's manifest flow. The callback is unauthenticated and outside {@code /api}, like
 * {@link GithubCallbackController}. The App is deployment-wide; the org and project slugs are only where to redirect
 * back to. {@link GithubManifestExchange} is a seam so tests need no call to {@code api.github.com}.
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
        // The App is a deployment-wide singleton and requireOwner() only proves ownership of the calling org, so fail
        // before minting state rather than let any org owner overwrite it.
        if (appConfig.isAppConfigured()) {
            throw new TessaryException(GitError.APP_ALREADY_CONFIGURED);
        }
        String s = state.mint(orgSlug, projectSlug, r.project().id());
        String base = frontendBase();

        // Inactive: the platform has no push-webhook endpoint, and a live hook would POST every push to a 404.
        // {@code url} is omitted rather than pointed at a dead path.
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

        String manifestJson = mapper.valueToTree(manifest).toString();
        String url = "https://github.com/settings/apps/new?state=" + URLEncoder.encode(s, StandardCharsets.UTF_8);
        return ApiResponse.ok(new ManifestStartView(url, manifestJson));
    }

    /**
     * The signed {@code state} is the credential. The {@code code} is one-shot, so a resubmitted callback surfaces
     * GitHub's rejection as {@link GitError#MANIFEST_CONVERSION_FAILED}.
     */
    @GetMapping("/git/github/manifest/callback")
    public ResponseEntity<Void> callback(
            @RequestParam("state") String stateParam, @RequestParam(value = "code", required = false) String code) {
        StatePayload p = state.verify(stateParam);
        try {
            if (code == null || code.isBlank()) {
                throw new TessaryException(GitError.MANIFEST_CONVERSION_FAILED);
            }
            JsonNode app = exchange.convert(code);
            appConfig.persist(
                    app.path("id").asText(),
                    app.path("pem").asText(),
                    app.path("slug").asText(),
                    app.path("client_id").asText(),
                    app.path("client_secret").asText());
            log.info("github app config captured for org={} project={}", p.orgSlug(), p.projectSlug());
            return redirect(setupConnected(p));
        } catch (TessaryException e) {
            log.warn("github manifest conversion failed for project {}: {}", p.projectId(), e.getMessage());
            return redirect(setupError(p, e.error().code()));
        }
    }

    // Settings -> Git integration handles these params, and the new App is installed from there.
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
import ai.tessary.git.GitIntegrationDtos.ManifestStartView;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Mirrors {@link GithubCallbackControllerTest}'s structure: the state-verify + redirect logic is
 * exercised directly, and the one outbound GitHub call (manifest-code conversion) is mocked via
 * {@link GithubManifestExchange} rather than hitting {@code api.github.com}.
 */
class GithubManifestControllerTest {

    private GithubInstallStateService state;
    private GithubAppConfigService appConfig;
    private TenantPathResolver resolver;
    private GithubManifestExchange exchange;
    private GithubManifestController controller;
    private final ObjectMapper mapper = new ObjectMapper();

    private final TenantContext ctx = new TenantContext("u", "e@x.io", "o", "p1", "owner", null);

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 5);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    @BeforeEach
    void setUp() {
        state = new GithubInstallStateService(secretBox(), mapper);
        appConfig = mock(GithubAppConfigService.class);
        resolver = mock(TenantPathResolver.class);
        exchange = mock(GithubManifestExchange.class);
        AuthProperties workos = new AuthProperties();
        workos.setFrontendUrl("https://app.example.com/");
        controller = new GithubManifestController(state, appConfig, resolver, workos, mapper, exchange);
        Resolved resolved = new Resolved(
                new Organization("o", "wo", "acme", "Acme", "t", null, null),
                new Project("p1", "o", "web", "Web", "d", "t", null, null, true, null),
                "owner");
        when(resolver.requireProject(ctx, "acme", "web")).thenReturn(resolved);
    }

    private static String location(ResponseEntity<Void> resp) {
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        return resp.getHeaders().getLocation().toString();
    }

    // ---- manifest-url --------------------------------------------------------

    @Test
    void manifestUrl_buildsPostableManifestWithSignedState() {
        ApiResponse<ManifestStartView> res = controller.manifestUrl(ctx, "acme", "web");
        ManifestStartView view = res.data();
        assertTrue(view.url().startsWith("https://github.com/settings/apps/new?state="), view.url());

        JsonNode manifest = readManifest(view.manifest());
        // No push-webhook endpoint survives Track A, so the App is minted with its hook off and no url.
        assertEquals(false, manifest.path("hook_attributes").path("active").asBoolean(true));
        assertTrue(manifest.path("hook_attributes").path("url").isMissingNode());
        assertEquals(
                "https://app.example.com/git/github/manifest/callback",
                manifest.path("redirect_url").asText());
        assertEquals(
                "read", manifest.path("default_permissions").path("contents").asText());
        assertTrue(manifest.path("request_oauth_on_install").asBoolean());
        assertEquals(false, manifest.path("public").asBoolean(true));
    }

    @Test
    void manifestUrl_refusesWhenAnAppIsAlreadyConfigured() {
        // github_app_config is a deployment-wide singleton shared by every org; requireOwner() above
        // only proves ownership of the CALLING org, so this second gate is what stops any org owner
        // from overwriting an App every other org on the deployment already depends on (see
        // GithubAppConfigService#persist's javadoc).
        when(appConfig.isAppConfigured()).thenReturn(true);

        ai.tessary.open.errors.TessaryException e = org.junit.jupiter.api.Assertions.assertThrows(
                ai.tessary.open.errors.TessaryException.class, () -> controller.manifestUrl(ctx, "acme", "web"));
        assertEquals(ai.tessary.open.errors.GitError.APP_ALREADY_CONFIGURED, e.error());
    }

    private JsonNode readManifest(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- callback --------------------------------------------------------

    @Test
    void callback_successfulConversion_persistsAndRedirectsConnected() {
        String stateParam = state.mint("acme", "web", "p1");
        JsonNode appNode = mapper.createObjectNode()
                .put("id", "999")
                .put("pem", "-----BEGIN RSA PRIVATE KEY-----\nx\n-----END RSA PRIVATE KEY-----")
                .put("webhook_secret", "whsec")
                .put("slug", "tessary-byo")
                .put("client_id", "cid")
                .put("client_secret", "csec");
        when(exchange.convert("mcode")).thenReturn(appNode);

        ResponseEntity<Void> resp = controller.callback(stateParam, "mcode");

        assertTrue(
                location(resp).endsWith("/orgs/acme/projects/web/settings/git?github_app_connected=1"), location(resp));
        verify(appConfig).persist("999", appNode.path("pem").asText(), "whsec", "tessary-byo", "cid", "csec");
    }

    @Test
    void callback_missingCode_redirectsErrorWithoutCallingExchange() {
        String stateParam = state.mint("acme", "web", "p1");

        ResponseEntity<Void> resp = controller.callback(stateParam, null);

        String loc = location(resp);
        assertTrue(loc.contains("/settings/git?github_error="), loc);
        verify(exchange, never()).convert(anyString());
    }

    @Test
    void callback_reusedOrExpiredCode_surfacesDistinctError() {
        // GitHub invalidates a manifest code after first use; the exchange throws
        // MANIFEST_CONVERSION_FAILED on the resubmitted attempt, not a generic app-config error.
        String stateParam = state.mint("acme", "web", "p1");
        when(exchange.convert("reused"))
                .thenThrow(new ai.tessary.open.errors.TessaryException(
                        ai.tessary.open.errors.GitError.MANIFEST_CONVERSION_FAILED));

        ResponseEntity<Void> resp = controller.callback(stateParam, "reused");

        assertTrue(location(resp).contains("GIT.MANIFEST_CONVERSION_FAILED"), location(resp));
        verify(appConfig, never())
                .persist(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void callback_invalidState_throwsRatherThanRedirecting() {
        // No verified project to redirect to — mirrors GithubCallbackController's same choice.
        org.junit.jupiter.api.Assertions.assertThrows(
                ai.tessary.open.errors.TessaryException.class, () -> controller.callback("garbage", "code"));
    }
}

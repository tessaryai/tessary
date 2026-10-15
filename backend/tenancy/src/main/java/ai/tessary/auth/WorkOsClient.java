// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin REST client over WorkOS user management, the BYO {@link AuthProvider}; the SDK is skipped because it lags and
 * only a few endpoints are needed. Wired only by {@link AuthProviderConfig}. {@code /authenticate} takes the API key as
 * {@code client_secret} in the body; the invitation endpoints take it as a bearer header.
 */
public class WorkOsClient implements AuthProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkOsClient.class);
    private static final String BASE = "https://api.workos.com";

    private final WorkOsProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public WorkOsClient(WorkOsProperties props, ObjectMapper mapper) {
        this(
                props,
                mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** The transport is a parameter so a test can answer without the network. */
    WorkOsClient(WorkOsProperties props, ObjectMapper mapper, HttpClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String authorizationUrl(String state) {
        String qs = "response_type=code"
                + "&client_id=" + enc(props.getClientId())
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&provider=authkit"
                + (state != null && !state.isBlank() ? "&state=" + enc(state) : "");
        return BASE + "/user_management/authorize?" + qs;
    }

    @Override
    public String signOutUrl(@Nullable String sessionId, String returnTo) {
        if (sessionId == null) return returnTo;
        return BASE + "/user_management/sessions/logout?session_id=" + enc(sessionId) + "&return_to=" + enc(returnTo);
    }

    @Override
    public AuthResult authenticateWithCode(String code) {
        JsonNode body = post(
                "/user_management/authenticate",
                Map.of(
                        "client_id",
                        props.getClientId(),
                        "client_secret",
                        props.getApiKey(),
                        "grant_type",
                        "authorization_code",
                        "code",
                        code));
        return AuthResult.from(body);
    }

    @Override
    public AuthResult refresh(String refreshToken, @Nullable String organizationId) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("client_id", props.getClientId());
        payload.put("client_secret", props.getApiKey());
        payload.put("grant_type", "refresh_token");
        payload.put("refresh_token", refreshToken);
        if (organizationId != null) payload.put("organization_id", organizationId);
        JsonNode body = post("/user_management/authenticate", payload);
        return AuthResult.from(body);
    }

    /**
     * Omits {@code organization_id} and {@code role_slug}: the local {@code org_invitation} table grants membership and
     * role.
     */
    @Override
    public Invitation createInvitation(String email) {
        JsonNode body = postBearer("/user_management/invitations", Map.of("email", email));
        return new Invitation(body.path("id").asText(null));
    }

    /** Best-effort; surfaces failure to the caller. */
    @Override
    public void revokeInvitation(String workosInvitationId) {
        postBearer("/user_management/invitations/" + enc(workosInvitationId) + "/revoke", Map.of());
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return send(path, body, false);
    }

    private JsonNode postBearer(String path, Map<String, Object> body) {
        return send(path, body, true);
    }

    private JsonNode send(String path, Map<String, Object> body, boolean bearer) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20));
            if (bearer) req.header("Authorization", "Bearer " + props.getApiKey());
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s / 100 != 2) {
                // WARN egresses to Loki and the message reaches the client; the upstream body can carry WorkOS user
                // detail, so it stays at DEBUG.
                log.warn("WorkOS {} -> {}", path, s);
                log.debug("WorkOS {} -> {} body={}", path, s, truncate(res.body(), 200));
                throw new AuthException("workos " + path + " returned " + s);
            }
            return mapper.readTree(res.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AuthException("workos call failed: " + e.getMessage(), e);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("workos call failed: " + e.getMessage(), e);
        }
    }

    private static String enc(@Nullable String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String truncate(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

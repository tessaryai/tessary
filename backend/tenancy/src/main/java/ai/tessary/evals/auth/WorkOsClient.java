// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

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
 * Thin REST client over the WorkOS user-management API, and one of two {@link AuthProvider}
 * implementations — the BYO-credentials one. We don't pull the official Java SDK because (a) its versioning lags
 * Python/Node and (b) we only need three endpoints. Everything else hangs off the sealed cookie we
 * issue ourselves.
 *
 * <p>No longer a {@code @Component}: it is wired exclusively via {@link AuthProviderConfig}'s
 * single branching {@code @Bean} method, which picks this class when {@link
 * WorkOsProperties#isEnabled()} is true and {@link PasswordAuthProvider} (#852) otherwise — not
 * {@code @ConditionalOnMissingBean}, since that shape only works with exactly one candidate bean
 * and this method has to choose between two. See {@link AuthProviderConfig}'s class Javadoc for
 * why.
 *
 * <p>Endpoints used:</p>
 * <ul>
 *   <li>{@code GET /user_management/authorize?...} — returns the AuthKit redirect URL</li>
 *   <li>{@code POST /user_management/authenticate} (grant_type=authorization_code)</li>
 *   <li>{@code POST /user_management/authenticate} (grant_type=refresh_token)</li>
 *   <li>{@code GET /user_management/sessions/logout?...} — returns the WorkOS logout URL</li>
 *   <li>{@code POST /user_management/invitations} — sends an invitation email</li>
 *   <li>{@code POST /user_management/invitations/{id}/revoke} — revokes a pending invitation</li>
 * </ul>
 *
 * <p>The {@code /authenticate} endpoint authenticates by carrying the API key as
 * {@code client_secret} in the body; the plain REST endpoints (invitations) use a
 * {@code Authorization: Bearer <api_key>} header instead.</p>
 */
public class WorkOsClient implements AuthProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkOsClient.class);
    private static final String BASE = "https://api.workos.com";

    private final WorkOsProperties props;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    public WorkOsClient(WorkOsProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Build the AuthKit authorisation URL the browser is redirected to. */
    @Override
    public String authorizationUrl(String state) {
        String qs = "response_type=code"
                + "&client_id=" + enc(props.getClientId())
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&provider=authkit"
                + (state != null && !state.isBlank() ? "&state=" + enc(state) : "");
        return BASE + "/user_management/authorize?" + qs;
    }

    /** Exchange an authorization code for tokens + user. */
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

    /** Use a refresh token to mint a fresh session. */
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
     * Send a WorkOS invitation email. We deliberately omit {@code organization_id}
     * and {@code role_slug}: the invitation only gets the recipient to sign up — the
     * local {@code org_invitation} table is what actually grants org membership and
     * carries the role, consumed on the invitee's first login.
     */
    @Override
    public Invitation createInvitation(String email) {
        JsonNode body = postBearer("/user_management/invitations", Map.of("email", email));
        return new Invitation(
                body.path("id").asText(null), body.path("accept_invitation_url").asText(null));
    }

    /** Revoke a previously-sent invitation. Best-effort; surfaces failure to the caller. */
    @Override
    public void revokeInvitation(String workosInvitationId) {
        postBearer("/user_management/invitations/" + enc(workosInvitationId) + "/revoke", Map.of());
    }

    /** Build a WorkOS-signed logout URL; client should redirect the browser to it. */
    public String getLogoutUrl(String sessionId, @Nullable String returnTo) {
        StringBuilder qs = new StringBuilder("session_id=").append(enc(sessionId));
        if (returnTo != null && !returnTo.isBlank()) qs.append("&return_to=").append(enc(returnTo));
        return BASE + "/user_management/sessions/logout?" + qs;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s / 100 != 2) {
                // WARN egresses to Loki and the exception message surfaces to the client; keep both
                // categorical (path + status). The upstream body can carry WorkOS account/user detail —
                // keep it local at DEBUG only.
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

    private JsonNode postBearer(String path, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s / 100 != 2) {
                // WARN egresses to Loki and the exception message surfaces to the client; keep both
                // categorical (path + status). The upstream body can carry WorkOS account/user detail —
                // keep it local at DEBUG only.
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

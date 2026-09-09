// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WorkOS AuthKit configuration. Bound from env vars (Spring relaxed binding):
 * <ul>
 *   <li>{@code WORKOS_API_KEY} — server-side API key (sk_*)</li>
 *   <li>{@code WORKOS_CLIENT_ID} — public client id (client_*)</li>
 *   <li>{@code WORKOS_REDIRECT_URI} — OAuth callback URL (must match WorkOS dashboard)</li>
 * </ul>
 * The session/cookie/app config that used to live here (cookie name, max-age, secure flag, cookie
 * password, frontend URL) is provider-agnostic and lives on {@link AuthProperties} instead — this
 * class is WorkOS-specific only, and only {@link WorkOsClient} (via {@link AuthProviderConfig})
 * depends on it directly.
 *
 * <p>When {@code apiKey} or {@code clientId} is blank there is no identity provider. Since #924 that
 * alone does NOT open the instance: {@link AuthFilter} additionally requires
 * {@link AuthProperties#isDisabled()}, and without it every guarded path answers 401. Production
 * must set both regardless -- {@link AuthRequiredInProdGuard} refuses to start without them.
 */
@Component
@ConfigurationProperties(prefix = "workos")
public class WorkOsProperties {

    private String apiKey = "";
    private String clientId = "";
    private String redirectUri = "http://localhost:8000/auth/callback";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    /** True when API key + client id are both set; used by AuthFilter to decide if auth is enforced. */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && clientId != null && !clientId.isBlank();
    }
}

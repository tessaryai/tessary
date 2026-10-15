// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WorkOS AuthKit configuration ({@code WORKOS_API_KEY}, {@code WORKOS_CLIENT_ID}, {@code WORKOS_REDIRECT_URI}). When
 * the key or client id is blank, {@link AuthProviderConfig} falls back to {@link PasswordAuthProvider}; that never
 * opens the instance, since {@link AuthFilter} bypasses only under {@link AuthProperties#isDisabled()}.
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

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank() && clientId != null && !clientId.isBlank();
    }
}

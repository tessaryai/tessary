// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Provider-agnostic auth and session-cookie settings. Absent identity configuration fails closed;
 * {@code tessary.auth.disabled} is the deliberate act that opens an instance, authoritative regardless of provider (see
 * {@link AuthFilter#shouldNotFilter}).
 */
@Component
@ConfigurationProperties(prefix = "tessary.auth")
public class AuthProperties {

    /**
     * Serve every request unauthenticated. Negative polarity on purpose: {@code TESSARY_AUTH_DISABLED=true} reads as
     * alarming in any config diff, and the safe default is false.
     */
    private boolean disabled;

    /**
     * Distinct from ux-explorer's {@code wos-session} so the two apps don't fight over a cookie on a shared parent
     * domain.
     */
    private String cookieName = "tessary-session";

    /**
     * Seven days. Logout is client-side with no revocation list, so a shorter window caps a stolen cookie's blast
     * radius.
     */
    private long cookieMaxAgeSeconds = 60L * 60 * 24 * 7;

    /** Whether to set the {@code Secure} cookie flag. Disable for local http dev. */
    private boolean cookieSecure = false;

    /**
     * Base64 32-byte AES key {@link SessionCipher} seals the cookie with, separate from the ingestion-credential key.
     */
    private String cookiePassword = "";

    /** Where to send users after login/logout, and the base URL device-link confirmations point at. */
    private String frontendUrl = "http://localhost:8000/";

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public long getCookieMaxAgeSeconds() {
        return cookieMaxAgeSeconds;
    }

    public void setCookieMaxAgeSeconds(long cookieMaxAgeSeconds) {
        this.cookieMaxAgeSeconds = cookieMaxAgeSeconds;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookiePassword() {
        return cookiePassword;
    }

    public void setCookiePassword(String cookiePassword) {
        this.cookiePassword = cookiePassword;
    }

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }
}

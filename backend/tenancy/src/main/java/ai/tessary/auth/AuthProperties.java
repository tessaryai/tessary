// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Provider-agnostic auth/session configuration: the deployment's answer to "is this instance
 * allowed to serve unauthenticated requests?", plus the session-cookie and post-auth-redirect
 * settings every provider adapter shares.
 *
 * <p>The {@code disabled} flag is documented first because it's the load-bearing one — see below.
 * The remaining fields ({@code cookieName}, {@code cookieMaxAgeSeconds}, {@code cookieSecure},
 * {@code cookiePassword}, {@code frontendUrl}) moved here off {@link WorkOsProperties} (#851): none
 * of them are WorkOS-specific — they govern how {@link SessionCipher} seals the cookie and where
 * {@link AuthController}/{@link ai.tessary.auth.link.DeviceLinkController} redirect the
 * browser, regardless of which {@link AuthProvider} is configured.
 *
 * <p><b>{@code TESSARY_AUTH_DISABLED} ({@code tessary.auth.disabled}), default <b>false</b>.</b>
 * It exists because {@link AuthFilter} needs to tell two states apart that used to look identical:
 * an operator who deliberately runs an open instance, and an operator whose identity provider is
 * simply not configured yet.
 *
 * <p><b>Why this is a separate property rather than an absence.</b> Before #924, "no auth" was
 * inferred from {@code WorkOsProperties.isEnabled()} returning false — so a missing credential
 * silently opened every {@code /api/**} and {@code /mcp} path. The open edition has no identity
 * provider of its own until epic 2 lands one, which makes "unconfigured" the NORMAL state of a
 * self-hosted instance rather than an unusual one, and inferring consent from a normal state is
 * how a platform ends up on the internet with no authentication. Absent configuration now fails
 * CLOSED; opening the instance takes a deliberate act that this property records.
 *
 * <p>Disabling auth is a real and supported thing to do — it is how the dev stack and the
 * integration suite run — so it is a documented setting rather than a hidden one. It is
 * authoritative on its own, regardless of which {@link AuthProvider} is active or how it answers
 * {@code isEnabled()} (re-decided by #852/#996 — see {@link AuthFilter#shouldNotFilter}'s own
 * comment for why the earlier "a configured provider always wins over the flag" precedence could
 * not survive {@link PasswordAuthProvider} always being enabled).
 */
@Component
@ConfigurationProperties(prefix = "tessary.auth")
public class AuthProperties {

    /**
     * When true, every request is served unauthenticated -- on its own, regardless of which
     * {@link AuthProvider} is configured (re-decided by #852/#996; see the class Javadoc above).
     * Default false, so an instance refuses requests instead of opening them.
     *
     * <p><b>Negative polarity, deliberately, and it is the only one in the codebase</b> -- the
     * other eight deploy switches are all {@code *.enabled}. An {@code tessary.auth.enabled} spelt
     * the usual way would have to default to TRUE to be safe, and a property whose default is true
     * is invisible: nothing in a config file or an env dump would show that authentication is on,
     * and the day someone sets it false to get a dev box working, nothing would show that either.
     * Naming the dangerous state is what makes it greppable -- {@code TESSARY_AUTH_DISABLED=true}
     * appears in the compose file, in the env, and in the startup warning, and it reads as alarming
     * in all three, which is the intent.
     */
    private boolean disabled;

    /**
     * Cookie name used to carry the sealed session. Distinct from ux-explorer's
     * {@code wos-session} so the two apps don't fight over a single cookie even
     * if deployed on the same parent domain. SSO across the two still works via
     * WorkOS AuthKit (each app does its own /auth/callback).
     */
    private String cookieName = "tessary-session";

    /**
     * Cookie Max-Age in seconds; default 7 days (down from the original 30).
     * AuthFilter refreshes via the configured provider on every request whose access token has
     * expired, so the cookie's persistence only governs how long after a
     * period of inactivity a user stays signed in. A shorter window caps the
     * blast radius of a stolen cookie since logout is purely client-side and
     * there is no server-side session revocation list.
     */
    private long cookieMaxAgeSeconds = 60L * 60 * 24 * 7;

    /** Whether to set the {@code Secure} cookie flag. Disable for local http dev. */
    private boolean cookieSecure = false;

    /** Base64 32-byte AES key ({@code TESSARY_AUTH_COOKIE_PASSWORD}) {@link SessionCipher} seals the
     *  session cookie with, so a leaked ingestion-credential key never compromises sessions and vice versa. */
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The identity-provider seam. Everything {@link AuthController}, {@link AuthFilter}, and
 * {@link ai.tessary.tenant.OrganizationController} need from an identity provider — mint an
 * authorization URL, exchange a code, refresh a session, and manage org invitations — lives here,
 * behind an interface, so none of those call sites (nor {@link SessionCipher},
 * {@link AuthPostureAnnouncer}, {@link AuthRequiredInProdGuard}, or
 * {@link ai.tessary.auth.link.DeviceLinkController}) hold a direct reference to
 * {@link WorkOsClient} or {@link WorkOsProperties} any more.
 *
 * <p>{@link WorkOsClient} (BYO WorkOS credentials) and {@link PasswordAuthProvider} (the
 * dependency-free open-edition default, #852) are the two implementations, wired via
 * {@link AuthProviderConfig}. Session sealing ({@link SealedSession}, {@link SessionCipher}) stays
 * on the provider-agnostic side of this seam — a {@code SealedSession} is built from an
 * {@link AuthResult}, which this interface owns, not from anything WorkOS-shaped directly.
 *
 * <p>The nested types below are moved here verbatim from {@code WorkOsClient} — same field names,
 * same order, same nullability — so every existing call site's accessor chain (notably
 * {@link AuthFilter}'s refresh-path null-coalescing over {@code r.workosUserId()/email()/...})
 * keeps compiling and behaving unchanged. The field names still say "workos" because that is the
 * wire shape WorkOS returns and the DB column {@link PasswordAuthProvider} (#852) also populates,
 * with a synthetic id, for its own principals; renaming them is out of scope here.
 */
public interface AuthProvider {

    /**
     * True when this provider has enough configuration to be used — e.g. an API key and client id
     * are both set. {@link AuthFilter} treats "no provider configured" as the normal state of a
     * self-hosted instance (see {@link AuthProperties}), not as consent to serve requests
     * unauthenticated.
     */
    boolean isEnabled();

    /** Build the URL the browser is redirected to in order to sign in, carrying the given CSRF state. */
    String authorizationUrl(String state);

    /** Exchange an authorization code for tokens + user. */
    AuthResult authenticateWithCode(String code) throws AuthException;

    /** Use a refresh token to mint a fresh session. */
    AuthResult refresh(String refreshToken, @Nullable String organizationId);

    /** Send an invitation email for the given address. */
    Invitation createInvitation(String email);

    /** Revoke a previously-sent invitation. Best-effort; surfaces failure to the caller. */
    void revokeInvitation(String invitationId);

    /**
     * Whether this provider drives the OAuth redirect dance ({@code authorizationUrl}/
     * {@code authenticateWithCode}, called from {@code GET /auth/login}/{@code /auth/callback}).
     * True for every provider until {@code PasswordAuthProvider} (#852), which overrides this to
     * {@code false} so {@link AuthController} degrades those two GET routes to a frontend redirect
     * instead of calling a method that provider has no way to implement.
     */
    default boolean supportsRedirectFlow() {
        return true;
    }

    /**
     * Create an account from an email/password pair. Defaults to a clean 4xx via
     * {@link AuthException} so a stray {@code POST /auth/signup} while a redirect-flow provider
     * (WorkOS) is active fails predictably instead of needing every implementation to know about
     * every other provider's routes. Overridden by {@code PasswordAuthProvider}, the one
     * implementation that can actually do this.
     */
    default AuthResult signupWithCredentials(String email, String password) throws AuthException {
        throw new AuthException("credential auth not supported by this provider");
    }

    /** Authenticate an email/password pair. Same default-throws shape as {@link #signupWithCredentials}. */
    default AuthResult authenticateWithCredentials(String email, String password) throws AuthException {
        throw new AuthException("credential auth not supported by this provider");
    }

    /**
     * Parsed response from an authenticate/refresh call. Field names mirror the WorkOS wire shape.
     * Token expiry is computed at parse time (now + expires_in seconds) so callers don't have to do
     * the math.
     */
    record AuthResult(
            @Nullable String accessToken,
            @Nullable String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable String workosUserId,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String profilePictureUrl,
            @Nullable String organizationId,
            @Nullable String sessionId) {

        public static AuthResult from(JsonNode body) {
            JsonNode user = body.path("user");
            long expiresIn = body.path("expires_in").asLong(60L * 60); // default 1h
            return new AuthResult(
                    body.path("access_token").asText(null),
                    body.path("refresh_token").asText(null),
                    Instant.now().plusSeconds(expiresIn),
                    user.path("id").asText(null),
                    user.path("email").asText(null),
                    user.path("first_name").asText(null),
                    user.path("last_name").asText(null),
                    user.path("profile_picture_url").asText(null),
                    body.path("organization_id").isMissingNode()
                                    || body.path("organization_id").isNull()
                            ? null
                            : body.path("organization_id").asText(),
                    body.path("session_id").asText(null));
        }

        public @Nullable String displayName() {
            if (firstName == null && lastName == null) return email;
            return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        }
    }

    /** Result of sending an invitation. */
    record Invitation(@Nullable String id, @Nullable String acceptInvitationUrl) {}

    /** Signals a failure talking to the identity provider — caller decides how to surface it (usually 502). */
    final class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

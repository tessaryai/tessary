// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Session sealing stays provider-agnostic: a {@code SealedSession} is built from an {@link AuthResult}. Field names
 * still say "workos" because that is the wire shape and the DB column the password provider also fills.
 */
public interface AuthProvider {

    String authorizationUrl(String state);

    AuthResult authenticateWithCode(String code) throws AuthException;

    AuthResult refresh(String refreshToken, @Nullable String organizationId);

    Invitation createInvitation(String email);

    void revokeInvitation(String invitationId);

    /**
     * False only for {@code PasswordAuthProvider}, so {@link AuthController} degrades the two GET routes to a frontend
     * redirect.
     */
    default boolean supportsRedirectFlow() {
        return true;
    }

    /**
     * A provider with its own sign-in session (WorkOS AuthKit) must end it here, or the next sign-in skips the prompt.
     */
    default String signOutUrl(@Nullable String sessionId, String returnTo) {
        return returnTo;
    }

    /**
     * Defaults to {@link AuthException} so a stray credential POST under a redirect-flow provider fails as a clean 4xx.
     */
    default AuthResult signupWithCredentials(String email, String password) throws AuthException {
        throw new AuthException("credential auth not supported by this provider");
    }

    default AuthResult authenticateWithCredentials(String email, String password) throws AuthException {
        throw new AuthException("credential auth not supported by this provider");
    }

    /** Expiry is computed at parse time. */
    record AuthResult(
            @Nullable String accessToken,
            @Nullable String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable String workosUserId,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String profilePictureUrl,
            @Nullable String organizationId) {

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
                            : body.path("organization_id").asText());
        }

        /**
         * The signature is not checked: the token came straight from the provider over TLS and the id only goes back to
         * it. Null for a non-JWT token.
         */
        public @Nullable String sessionId() {
            if (accessToken == null) return null;
            String[] parts = accessToken.split("\\.", -1);
            if (parts.length != 3) return null;
            try {
                byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
                JsonNode sid = JWT_READER
                        .readTree(new String(payload, StandardCharsets.UTF_8))
                        .path("sid");
                return sid.isTextual() ? sid.asText() : null;
            } catch (IllegalArgumentException | java.io.IOException e) {
                return null;
            }
        }

        private static final ObjectMapper JWT_READER = new ObjectMapper();

        public @Nullable String displayName() {
            if (firstName == null && lastName == null) return email;
            return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        }
    }

    record Invitation(@Nullable String id) {}

    final class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

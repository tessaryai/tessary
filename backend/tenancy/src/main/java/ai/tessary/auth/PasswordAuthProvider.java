// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategy;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The dependency-free, zero-cloud-credential {@link AuthProvider}: this build's default.
 * {@link #isEnabled()} is unconditionally {@code true} — unlike {@link WorkOsClient}, there is no
 * external configuration that could be missing.
 *
 * <p>Every WorkOS-shaped downstream call site ({@link AuthFilter#resolveCookie},
 * {@code TenantService.upsertUserFromWorkos}, the {@code auth.workos_failed}-style error handling
 * in {@link AuthController}) keys off {@link Principal#workosUserId()}. Rather than touch any of
 * those signatures, this provider mints a synthetic-but-stable id of the form
 * {@code "local_" + Ids.ulid()} at signup and returns that same id, unchanged, on every subsequent
 * login — which is what keeps {@code AuthFilter}'s {@code findByWorkosId} lookup resolving to the
 * same principal across sessions. It does NOT implement the OAuth redirect dance: see
 * {@link #supportsRedirectFlow()}.
 *
 * <p>Not wired via {@code @Component}, for the same {@code NoUniqueBeanDefinitionException} reason
 * documented on {@link WorkOsClient}: it is one of two candidate {@link AuthProvider} beans, and
 * {@link AuthProviderConfig} is what decides which one wins.
 */
public class PasswordAuthProvider implements AuthProvider {

    private static final String SYNTHETIC_ID_PREFIX = "local_";

    /**
     * INVARIANT: the hasher and the verifier must carry the SAME long-password strategy, or a password
     * this class hashed is one it cannot verify.
     *
     * <p>bcrypt itself reads at most 72 bytes. {@code BCrypt.withDefaults()} pairs that with
     * {@code LongPasswordStrategies.strict()}, which THROWS past the cap rather than deriving — so a
     * passphrase inside the 200-character contract {@code AuthController.SignupRequest} advertises left
     * this method as an {@code IllegalArgumentException}, and {@code GlobalExceptionHandler} rendered it
     * as a 400 carrying bcrypt's own wording. The cap is bytes, not characters, so a non-Latin or emoji
     * password crossed it around 24 characters. SHA-512 pre-derivation accepts any length instead.
     *
     * <p>It does not invalidate a hash already stored: {@code BaseLongPasswordStrategy.derive} only
     * derives at or past the cap and returns the same reference below it, so every password short enough
     * to have been hashable under the old strategy hashes byte-identically under this one.
     */
    private static final LongPasswordStrategy LONG_PASSWORDS =
            LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2A);

    private final PrincipalRepository users;
    private final AuthProperties authProperties;

    public PasswordAuthProvider(PrincipalRepository users, AuthProperties authProperties) {
        this.users = users;
        this.authProperties = authProperties;
    }

    @Override
    public boolean isEnabled() {
        // No external config to be missing — this is the always-available default.
        return true;
    }

    @Override
    public boolean supportsRedirectFlow() {
        return false;
    }

    @Override
    public String authorizationUrl(String state) {
        // Unreachable once AuthController's supportsRedirectFlow() guard is in place — GET
        // /auth/login degrades to a frontend redirect instead of calling this. Kept as a loud
        // failure rather than a silent one in case some future call site forgets the guard.
        throw new UnsupportedOperationException(
                "PasswordAuthProvider has no OAuth redirect flow; guard callers on supportsRedirectFlow()");
    }

    @Override
    public AuthResult authenticateWithCode(String code) {
        throw new UnsupportedOperationException(
                "PasswordAuthProvider has no OAuth redirect flow; guard callers on supportsRedirectFlow()");
    }

    @Override
    public AuthResult refresh(String refreshToken, @Nullable String organizationId) {
        // Never called: signupWithCredentials/authenticateWithCredentials always return a null
        // refreshToken (see their Javadoc below), and AuthFilter.tryRefresh short-circuits cleanly
        // on old.refreshToken() == null before it would ever reach a provider's refresh().
        throw new UnsupportedOperationException("PasswordAuthProvider never issues a refresh token");
    }

    @Override
    public Invitation createInvitation(String email) {
        // Invitation bookkeeping already happens locally via org_invitation/InvitationRepository
        // (TenantService.consumePendingInvitations); the WorkOS call only fired a notification
        // email, which this dependency-free provider has no mechanism to send.
        return new Invitation(null, null);
    }

    @Override
    public void revokeInvitation(String invitationId) {
        // No-op — see createInvitation.
    }

    @Override
    public AuthResult signupWithCredentials(String email, String password) throws AuthException {
        if (email == null || email.isBlank()) {
            throw new AuthException("email is required");
        }
        if (password == null || password.isBlank()) {
            throw new AuthException("password is required");
        }
        if (users.findByEmail(email).isPresent()) {
            throw new AuthException("an account with this email already exists");
        }
        String now = Instant.now().toString();
        String workosUserId = SYNTHETIC_ID_PREFIX + Ids.ulid();
        String hash = BCrypt.with(BCrypt.Version.VERSION_2A, new SecureRandom(), LONG_PASSWORDS)
                .hashToString(ApiKeyService.BCRYPT_COST, password.toCharArray());
        Principal fresh = Principal.human(Ids.ulid(), workosUserId, email, null, null, now, now);
        try {
            users.insertWithPassword(fresh, hash);
        } catch (DataIntegrityViolationException e) {
            // The findByEmail check above is a pre-check, not a lock: two concurrent signups for
            // the same email can both pass it and race to insert. The loser hits app_user's email
            // unique constraint here rather than the check above — translate it to the same
            // AuthException the check-above path throws, so AuthController.signup's single catch
            // (AuthException -> 409 auth.signup_failed) covers both, instead of this leaking as an
            // unhandled 500 from GlobalExceptionHandler's generic fallback.
            throw new AuthException("an account with this email already exists", e);
        }
        return result(workosUserId, email, fresh.displayName(), fresh.avatarUrl());
    }

    @Override
    public AuthResult authenticateWithCredentials(String email, String password) throws AuthException {
        if (email == null || password == null) {
            throw new AuthException("invalid credentials");
        }
        Optional<PrincipalRepository.Credential> credential = users.findCredentialByEmail(email);
        if (credential.isEmpty()) {
            throw new AuthException("invalid credentials");
        }
        PrincipalRepository.Credential c = credential.get();
        BCrypt.Result verified = BCrypt.verifyer(BCrypt.Version.VERSION_2A, LONG_PASSWORDS)
                .verify(password.toCharArray(), c.passwordHash());
        if (!verified.verified) {
            throw new AuthException("invalid credentials");
        }
        // Re-read the row's current display fields rather than trust anything caller-supplied — a
        // login carries no display_name/avatar_url of its own to begin with.
        Principal current = users.findById(c.principalId()).orElseThrow(() -> new AuthException("invalid credentials"));
        // The stored workos_user_id, never a freshly-minted one — this is what keeps
        // AuthFilter.findByWorkosId resolving the same principal across logins.
        return result(current.workosUserId(), current.email(), current.displayName(), current.avatarUrl());
    }

    private AuthResult result(
            @Nullable String workosUserId,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl) {
        // No refreshToken: AuthController seals the session cookie with
        // AuthProperties.getCookieMaxAgeSeconds() as its own expiry, so the session simply
        // expires with the cookie instead of needing silent refresh. No accessToken either — nothing
        // downstream of this provider reads it for anything but presence (AuthController's
        // "malformed 2xx body" null-check), so a stable non-null placeholder satisfies that without
        // implying a real bearer credential exists anywhere.
        //
        // accessTokenExpiresAt reads the SAME AuthProperties.getCookieMaxAgeSeconds() that
        // AuthController.establishSession derives the Set-Cookie Max-Age from, so the two never
        // diverge if an operator overrides auth.cookie-max-age-seconds -- unlike a hardcoded
        // literal, which would drift from that config.
        return new AuthResult(
                "local-session",
                null,
                Instant.now().plusSeconds(authProperties.getCookieMaxAgeSeconds()),
                workosUserId,
                email,
                displayName,
                null,
                avatarUrl,
                null,
                null);
    }
}

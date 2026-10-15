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
 * Mints a stable {@code "local_" + Ids.ulid()} id at signup and returns it on every login, so the WorkOS-keyed call
 * sites keep resolving the same principal. Not a {@code @Component}: {@link AuthProviderConfig} picks between the two
 * {@link AuthProvider} beans.
 */
public class PasswordAuthProvider implements AuthProvider {

    private static final String SYNTHETIC_ID_PREFIX = "local_";

    /**
     * The hasher and verifier must share this strategy, or a stored hash cannot be verified. bcrypt reads at most 72
     * bytes and the default strategy throws past that (around 24 characters for non-Latin text); SHA-512 pre-derivation
     * accepts any length and leaves shorter passwords' hashes byte-identical.
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
    public boolean supportsRedirectFlow() {
        return false;
    }

    @Override
    public String authorizationUrl(String state) {
        // Unreachable behind AuthController's supportsRedirectFlow() guard; kept loud in case a future call site
        // forgets it.
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
        // Never called: these credentials carry no refresh token, and AuthFilter short-circuits on a null one.
        throw new UnsupportedOperationException("PasswordAuthProvider never issues a refresh token");
    }

    @Override
    public Invitation createInvitation(String email) {
        // Invitations are tracked locally; the WorkOS call only sent an email, which this provider cannot.
        return new Invitation(null);
    }

    @Override
    public void revokeInvitation(String invitationId) {
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
            // The findByEmail pre-check is not a lock: a concurrent signup can hit the unique constraint here, so
            // translate it to the same AuthException (409) instead of a 500.
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
        // A login carries no display fields of its own, so re-read the row.
        Principal current = users.findById(c.principalId()).orElseThrow(() -> new AuthException("invalid credentials"));
        // The stored id, never a fresh one, so AuthFilter.findByWorkosId resolves the same principal across logins.
        return result(current.workosUserId(), current.email(), current.displayName(), current.avatarUrl());
    }

    private AuthResult result(
            @Nullable String workosUserId,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl) {
        // No refresh token: the session expires with the cookie. The access token is a placeholder that only satisfies
        // a presence check. Expiry reads the same cookie max-age the Set-Cookie uses, so the two never diverge.
        return new AuthResult(
                "local-session",
                null,
                Instant.now().plusSeconds(authProperties.getCookieMaxAgeSeconds()),
                workosUserId,
                email,
                displayName,
                null,
                avatarUrl,
                null);
    }
}

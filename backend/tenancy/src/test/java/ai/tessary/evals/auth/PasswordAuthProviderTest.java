// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.tenant.Principal;
import ai.tessary.evals.tenant.PrincipalRepository;
import at.favre.lib.crypto.bcrypt.BCrypt;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordAuthProvider}, driven with a mocked {@link PrincipalRepository} on the
 * {@code AuthFilterPostureTest} precedent -- plain JUnit, no Spring context, no database.
 */
class PasswordAuthProviderTest {

    @Test
    @DisplayName("isEnabled() is unconditionally true -- no external config to be missing")
    void alwaysEnabled() {
        assertTrue(new PasswordAuthProvider(mock(PrincipalRepository.class), new AuthProperties()).isEnabled());
    }

    @Test
    @DisplayName("supportsRedirectFlow() is false -- no OAuth dance")
    void noRedirectFlow() {
        assertEquals(
                false,
                new PasswordAuthProvider(mock(PrincipalRepository.class), new AuthProperties()).supportsRedirectFlow());
    }

    @Test
    @DisplayName("signup: hashes the password, mints a synthetic workos_user_id, and inserts")
    void signupHashesAndInserts() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        AuthProvider.AuthResult r = provider.signupWithCredentials("new@example.com", "correct horse battery");

        assertEquals("new@example.com", r.email());
        assertNotNull(r.workosUserId());
        assertTrue(
                r.workosUserId().startsWith("local_"), "synthetic id must be recognisably local: " + r.workosUserId());
        assertNull(r.refreshToken(), "no refresh token is ever minted by this provider");

        var principalCaptor = org.mockito.ArgumentCaptor.forClass(Principal.class);
        var hashCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).insertWithPassword(principalCaptor.capture(), hashCaptor.capture());
        assertEquals("new@example.com", principalCaptor.getValue().email());
        assertEquals(r.workosUserId(), principalCaptor.getValue().workosUserId());
        assertTrue(
                BCrypt.verifyer().verify("correct horse battery".toCharArray(), hashCaptor.getValue()).verified,
                "the stored hash must verify against the original password");
    }

    @Test
    @DisplayName("signup: a duplicate email is rejected before any insert")
    void signupRejectsDuplicateEmail() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        when(users.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(Principal.human(
                        "p1", "workos-1", "taken@example.com", null, null, "2026-01-01T00:00:00Z", null)));
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        assertThrows(
                AuthProvider.AuthException.class,
                () -> provider.signupWithCredentials("taken@example.com", "whatever-password"));
        verify(users, never()).insertWithPassword(any(), any());
    }

    @Test
    @DisplayName("signup: a concurrent duplicate that races past the findByEmail pre-check "
            + "surfaces as AuthException (409), not the raw constraint violation (500)")
    void signupTranslatesConcurrentDuplicateEmailRace() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        // The pre-check sees no row yet -- a concurrent signup for the same email hasn't
        // committed. The insert itself is where the two racers actually collide.
        when(users.findByEmail("racing@example.com")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("app_user_email_key"))
                .when(users)
                .insertWithPassword(any(), any());
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        AuthProvider.AuthException ex = assertThrows(
                AuthProvider.AuthException.class,
                () -> provider.signupWithCredentials("racing@example.com", "whatever-password"));
        String message = ex.getMessage();
        assertNotNull(message, "AuthException always carries a user-facing message");
        assertTrue(
                message.contains("already exists"),
                "the race must surface the same user-facing message as the pre-check path: " + message);
    }

    @Test
    @DisplayName("signup: blank email or password is rejected")
    void signupRejectsBlankInput() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        assertThrows(AuthProvider.AuthException.class, () -> provider.signupWithCredentials("", "a-password"));
        assertThrows(AuthProvider.AuthException.class, () -> provider.signupWithCredentials("a@example.com", ""));
    }

    @Test
    @DisplayName("login: correct password against a stored hash succeeds and returns the stored workos_user_id")
    void loginSucceedsWithCorrectPassword() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        String hash = BCrypt.withDefaults().hashToString(10, "s3cret-pw".toCharArray());
        when(users.findCredentialByEmail("a@example.com"))
                .thenReturn(Optional.of(new PrincipalRepository.Credential("p1", "local_ABC123", hash)));
        when(users.findById("p1"))
                .thenReturn(Optional.of(Principal.human(
                        "p1", "local_ABC123", "a@example.com", "A Name", null, "2026-01-01T00:00:00Z", null)));
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        AuthProvider.AuthResult r = provider.authenticateWithCredentials("a@example.com", "s3cret-pw");

        assertEquals("local_ABC123", r.workosUserId());
        assertEquals("a@example.com", r.email());
        assertNull(r.refreshToken());
    }

    @Test
    @DisplayName("a password past bcrypt's 72-byte cap round-trips: signup hashes it, login verifies it")
    void longPasswordsRoundTrip() {
        // The cap is BYTES: the second case is 53 characters and 98 bytes, which is what a non-Latin or
        // emoji passphrase looks like well inside the 200-CHARACTER contract SignupRequest advertises.
        // Both used to throw out of hashToString under bcrypt's strict long-password strategy, surfacing
        // as a 400 carrying bcrypt's own wording rather than a field error.
        for (String password : new String[] {
            "correct-horse-battery-staple-".repeat(4), "пароль-который-длинный-очень-ок-и-ещё-немного-длиннее"
        }) {
            PrincipalRepository users = mock(PrincipalRepository.class);
            when(users.findByEmail("long@example.com")).thenReturn(Optional.empty());
            PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

            assertTrue(
                    password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72,
                    "the case is only interesting past the cap: " + password);
            provider.signupWithCredentials("long@example.com", password);

            var hashCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(users).insertWithPassword(any(Principal.class), hashCaptor.capture());
            String stored = hashCaptor.getValue();

            // The verifier must carry the same long-password strategy as the hasher, or this is where a
            // regression lands: a password the provider hashed that it then cannot verify.
            when(users.findCredentialByEmail("long@example.com"))
                    .thenReturn(Optional.of(new PrincipalRepository.Credential("p9", "local_LONG", stored)));
            when(users.findById("p9"))
                    .thenReturn(Optional.of(Principal.human(
                            "p9", "local_LONG", "long@example.com", null, null, "2026-01-01T00:00:00Z", null)));

            assertEquals(
                    "local_LONG",
                    provider.authenticateWithCredentials("long@example.com", password)
                            .workosUserId());
            assertThrows(
                    AuthProvider.AuthException.class,
                    () -> provider.authenticateWithCredentials("long@example.com", password + "-not"),
                    "a long password must not verify against a different long password");
        }
    }

    @Test
    @DisplayName("login: wrong password is rejected")
    void loginRejectsWrongPassword() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        String hash = BCrypt.withDefaults().hashToString(10, "s3cret-pw".toCharArray());
        when(users.findCredentialByEmail("a@example.com"))
                .thenReturn(Optional.of(new PrincipalRepository.Credential("p1", "local_ABC123", hash)));
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        assertThrows(
                AuthProvider.AuthException.class,
                () -> provider.authenticateWithCredentials("a@example.com", "wrong-password"));
    }

    @Test
    @DisplayName("login: unknown email is rejected, not distinguished from a wrong password")
    void loginRejectsUnknownEmail() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        when(users.findCredentialByEmail(eq("nobody@example.com"))).thenReturn(Optional.empty());
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        assertThrows(
                AuthProvider.AuthException.class,
                () -> provider.authenticateWithCredentials("nobody@example.com", "anything"));
    }

    @Test
    @DisplayName("cross-login stability: two logins for the same email return the same workos_user_id")
    void crossLoginStability() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        String hash = BCrypt.withDefaults().hashToString(10, "pw".toCharArray());
        when(users.findCredentialByEmail("a@example.com"))
                .thenReturn(Optional.of(new PrincipalRepository.Credential("p1", "local_STABLE", hash)));
        when(users.findById("p1"))
                .thenReturn(Optional.of(Principal.human(
                        "p1", "local_STABLE", "a@example.com", null, null, "2026-01-01T00:00:00Z", null)));
        PasswordAuthProvider provider = new PasswordAuthProvider(users, new AuthProperties());

        AuthProvider.AuthResult first = provider.authenticateWithCredentials("a@example.com", "pw");
        AuthProvider.AuthResult second = provider.authenticateWithCredentials("a@example.com", "pw");

        assertEquals(first.workosUserId(), second.workosUserId());
    }

    @Test
    @DisplayName("accessTokenExpiresAt tracks a configured cookieMaxAgeSeconds override, not the 7-day default")
    void sessionExpiryTracksConfiguredCookieMaxAge() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        AuthProperties authProperties = new AuthProperties();
        long oneHour = 60L * 60;
        authProperties.setCookieMaxAgeSeconds(oneHour);
        PasswordAuthProvider provider = new PasswordAuthProvider(users, authProperties);

        Instant before = Instant.now().plusSeconds(oneHour);
        AuthProvider.AuthResult r = provider.signupWithCredentials("new@example.com", "correct horse battery");
        Instant after = Instant.now().plusSeconds(oneHour);

        assertNotNull(r.accessTokenExpiresAt());
        assertTrue(
                !r.accessTokenExpiresAt().isBefore(before)
                        && !r.accessTokenExpiresAt().isAfter(after),
                "accessTokenExpiresAt must be derived from the configured cookieMaxAgeSeconds ("
                        + oneHour
                        + "s), not a hardcoded 7-day literal; got "
                        + r.accessTokenExpiresAt());
    }
}

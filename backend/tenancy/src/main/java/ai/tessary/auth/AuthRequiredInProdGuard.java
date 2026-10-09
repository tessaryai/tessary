// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the app when the {@code production} profile is active but
 * {@code TESSARY_AUTH_COOKIE_PASSWORD} is unset.
 *
 * <p>An identity provider is always present ({@link AuthProviderConfig} falls back to the
 * dependency-free {@link PasswordAuthProvider}), so the cookie password is the one piece of auth
 * configuration production can still be missing. Without it no session can be sealed, and the
 * instance would start and answer 401 to every browser until someone noticed. This fails the
 * deploy LOUDLY and IMMEDIATELY instead: a misconfigured production box should not boot at all.
 */
@Component
@Profile("production")
public class AuthRequiredInProdGuard {

    private final AuthProperties authProps;

    public AuthRequiredInProdGuard(AuthProperties authProps) {
        this.authProps = authProps;
    }

    @PostConstruct
    void verify() {
        if (authProps.getCookiePassword() == null
                || authProps.getCookiePassword().isBlank()) {
            throw new IllegalStateException("Refusing to start: production profile is active but "
                    + "TESSARY_AUTH_COOKIE_PASSWORD is unset. Generate one with "
                    + "`openssl rand -base64 32` and set it via SSM.");
        }
    }
}

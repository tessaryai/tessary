// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code production} profile without {@code TESSARY_AUTH_COOKIE_PASSWORD}. A provider is
 * always present, so the cookie password is the one thing production can lack, and without it every browser would get
 * 401.
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs the instance's authentication posture at startup; {@link AuthRequiredInProdGuard} refuses the one forbidden
 * state. {@code tessary.auth.disabled} is checked first: a provider is always configured even while
 * {@link AuthFilter#shouldNotFilter} bypasses everything, so announcing the provider first would say "enforced" while
 * auth is off.
 */
@Component
public class AuthPostureAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(AuthPostureAnnouncer.class);

    private final AuthProvider provider;
    private final AuthProperties auth;

    public AuthPostureAnnouncer(AuthProvider provider, AuthProperties auth) {
        this.provider = provider;
        this.auth = auth;
    }

    @PostConstruct
    void announce() {
        if (auth.isDisabled()) {
            log.warn(
                    "AUTH IS DISABLED: tessary.auth.disabled=true, so every /api/** and /mcp request is served "
                            + "unauthenticated, regardless of which identity provider ({}) is configured. This is "
                            + "correct for local development and the test suite, and is never correct on a reachable "
                            + "host.",
                    provider.getClass().getSimpleName());
        } else {
            log.info(
                    "Auth: {} active; /api/** and /mcp are enforced.",
                    provider.getClass().getSimpleName());
        }
    }
}

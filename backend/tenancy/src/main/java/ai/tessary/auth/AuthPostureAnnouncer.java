// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Says the instance's authentication posture out loud at startup.
 *
 * <p>Sibling of {@link AuthRequiredInProdGuard}: a bean whose only job is to look at the resolved
 * configuration once and tell the operator what it means. The guard refuses the boot in the one
 * state that must never reach production; this one narrates the states that are allowed but worth
 * knowing about. It lives outside {@link AuthFilter} because a one-shot startup announcement and a
 * per-request filter are different lifecycles, and because the whole posture table reads better as
 * one small file than folded into the filter.
 *
 * <p>{@code tessary.auth.disabled} is checked first and is authoritative on its own, regardless of
 * which {@link AuthProvider} is active: a provider can report itself enabled while
 * {@link AuthFilter#shouldNotFilter} is bypassing everything, so checking the provider first would
 * announce "enforced" while auth is actually off.
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
        } else if (provider.isEnabled()) {
            log.info(
                    "Auth: {} active; /api/** and /mcp are enforced.",
                    provider.getClass().getSimpleName());
        } else {
            // Only WorkOsClient can ever be unenabled here: PasswordAuthProvider has no external
            // config to be missing, so it is unconditionally enabled. This branch is dead today,
            // but stays: a future third AuthProvider could reintroduce a real "misconfigured, no
            // provider works" state.
            log.warn("No identity provider is configured, so /api/** and /mcp will answer 401. Configure one, "
                    + "or set tessary.auth.disabled=true to serve this instance unauthenticated on purpose.");
        }
    }
}

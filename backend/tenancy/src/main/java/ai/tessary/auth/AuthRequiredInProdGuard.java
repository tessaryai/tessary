// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the app when the {@code production} profile is active
 * but WorkOS isn't configured.
 *
 * <p>Its original justification — that {@code AuthFilter} bypassed everything when
 * {@code WorkOsProperties.isEnabled()} was false, so a missing
 * {@code WORKOS_API_KEY}/{@code WORKOS_CLIENT_ID} would silently open every {@code /api/**} and
 * {@code /mcp} endpoint — stopped being true: absent configuration now fails closed on its
 * own, in every profile, so this guard is no longer the only thing standing between a missing
 * credential and an open platform.
 *
 * <p>It is kept because it still does something the filter cannot: it fails the deploy LOUDLY and
 * IMMEDIATELY, rather than letting a production instance start and answer 401 to every request
 * until someone notices. A misconfigured production box should not boot at all.
 */
@Component
@Profile("production")
public class AuthRequiredInProdGuard {

    private final AuthProvider provider;
    private final AuthProperties authProps;

    public AuthRequiredInProdGuard(AuthProvider provider, AuthProperties authProps) {
        this.provider = provider;
        this.authProps = authProps;
    }

    @PostConstruct
    void verify() {
        if (!provider.isEnabled()) {
            // Do NOT offer "remove the production profile" here, which is what this message used to
            // say. That is the exact move that used to open the platform: dropping the
            // profile skipped this guard, and an unconfigured AuthFilter then served every path.
            // It no longer does -- absent configuration fails closed in every profile -- so the
            // old advice now buys an operator a stack that 401s instead of one that is open, and
            // either way it was advice to disable authentication printed by the check that exists
            // to require it.
            throw new IllegalStateException("Refusing to start: production profile is active but no identity "
                    + "provider is configured. Either set WORKOS_API_KEY and WORKOS_CLIENT_ID for the WorkOS "
                    + "adapter (BYO credentials), or use the dependency-free email/password provider, which is "
                    + "the open edition's default and needs no configuration to be \"enabled\" -- if you are "
                    + "seeing this with neither set, PasswordAuthProvider itself should have won the "
                    + "AuthProviderConfig bean selection and this guard should never fire; check for a stale "
                    + "AuthProvider bean override. Running with no provider at all is possible but never on a "
                    + "reachable host: it needs TESSARY_AUTH_DISABLED=true, which serves every request "
                    + "unauthenticated.");
        }
        if (authProps.getCookiePassword() == null
                || authProps.getCookiePassword().isBlank()) {
            throw new IllegalStateException("Refusing to start: production profile is active but "
                    + "TESSARY_AUTH_COOKIE_PASSWORD is unset. Generate one with "
                    + "`openssl rand -base64 32` and set it via SSM.");
        }
    }
}

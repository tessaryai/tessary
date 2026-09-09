// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.PrincipalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link AuthProvider} bean: WorkOS when configured (BYO, opt-in), {@link
 * PasswordAuthProvider} otherwise. The password provider is the default and must win whenever
 * WorkOS isn't set up.
 *
 * <p>This is a single branching {@code @Bean} method rather than two beans behind
 * {@code @ConditionalOnMissingBean}, because the choice has to track {@link
 * WorkOsProperties#isEnabled()} exactly, blank-vs-unset included, and {@code @ConditionalOnProperty}
 * only approximates that predicate (Spring's "property is present and non-'false'" semantics
 * aren't "both api-key and client-id are non-blank"). Branching on the same method every other
 * WorkOS-vs-not check in this package already calls guarantees bean selection and those checks, in
 * {@link AuthFilter}, {@link AuthRequiredInProdGuard} and {@link AuthPostureAnnouncer}, agree by
 * construction. {@link AuthProviderConfigTest} pins that parity directly.
 */
@Configuration(proxyBeanMethods = false)
public class AuthProviderConfig {

    /**
     * The active {@link AuthProvider}: WorkOS when the operator configured it (BYO credentials,
     * {@code WORKOS_API_KEY}/{@code WORKOS_CLIENT_ID} both set), {@link PasswordAuthProvider}
     * otherwise. The password provider is always constructible (no external configuration to be
     * missing), so this method never has a "neither" case to fall through to.
     */
    @Bean
    AuthProvider authProvider(
            WorkOsProperties workOsProps, ObjectMapper mapper, PrincipalRepository users, AuthProperties authProps) {
        if (workOsProps.isEnabled()) {
            return new WorkOsClient(workOsProps, mapper);
        }
        return new PasswordAuthProvider(users, authProps);
    }
}

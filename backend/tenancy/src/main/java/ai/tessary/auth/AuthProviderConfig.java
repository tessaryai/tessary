// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.PrincipalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WorkOS when configured, else {@link PasswordAuthProvider}. One branching {@code @Bean} rather than conditional beans,
 * because the choice must track {@link WorkOsProperties#isEnabled()} exactly (blank vs unset included) and agree with
 * {@link PublicOriginGuard}.
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

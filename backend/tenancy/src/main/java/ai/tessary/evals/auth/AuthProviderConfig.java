// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import ai.tessary.evals.tenant.PrincipalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link AuthProvider} bean, choosing between the two adapters that now exist: WorkOS
 * (BYO, opt-in) and {@link PasswordAuthProvider} (the open-edition default). Per the tessary-paid/OPEN-CORE.md
 * ledger — "{@code auth} gets a provider interface: dependency-free email/password default plus
 * the WorkOS adapter open for BYO credentials" — the password provider must actually win by
 * default, not merely coexist.
 *
 * <p><b>Why a single branching {@code @Bean} method and not the old dueling
 * {@code @ConditionalOnMissingBean} shape.</b> That shape only worked with exactly one candidate.
 * With two, the choice has to track {@link WorkOsProperties#isEnabled()} exactly — blank-vs-unset
 * included — and a {@code @ConditionalOnProperty} annotation only approximates that predicate
 * (Spring's "property is present and non-'false'" semantics are not "both api-key and client-id
 * are non-blank"). Branching directly on the same method every other WorkOS-vs-not decision in this
 * package already calls ({@link WorkOsProperties#isEnabled()}) is the only way to guarantee the
 * bean selection and every other {@code isEnabled()} check in {@link AuthFilter}/
 * {@link AuthRequiredInProdGuard}/{@link AuthPostureAnnouncer} agree, by construction, since they
 * all read the same method. {@link AuthProviderConfigTest} pins this parity directly rather than
 * assuming it from the code shape.
 */
@Configuration(proxyBeanMethods = false)
public class AuthProviderConfig {

    /**
     * The active {@link AuthProvider}: WorkOS when the operator configured it (BYO credentials —
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

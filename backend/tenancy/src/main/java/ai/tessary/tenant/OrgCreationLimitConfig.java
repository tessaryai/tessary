// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OPEN edition's {@link OrgCreationLimit} default, in the one shape that actually works.
 *
 * <p>Same trap, same fix as {@code OpenPlanConfig}/{@code FeatureFlagsConfig}: a {@code @Component}
 * on the implementation would need {@link ConditionalOnMissingBean} to step aside for the paid
 * override, and that annotation only works on a {@code @Bean} METHOD. Put it on a component-scanned
 * class instead and the scanner registers the definition before the condition is evaluated, so the
 * search finds the very bean it is guarding and the class cancels itself — the open context would
 * end up with no {@code OrgCreationLimit} bean at all, and {@code OrganizationController}'s
 * constructor injection would fail to start in EITHER edition. See {@code OpenPlanConfig}'s javadoc
 * for the commit that actually shipped that failure.
 *
 * <p>The paid override ({@code PaidOrgCreationLimit}) arrives as a component scanned by
 * {@code PaidPlanAutoConfiguration}, which registers during the parse phase — before this
 * {@code @Bean} method's {@link ConditionalOnMissingBean} condition is read — so it displaces this
 * default cleanly when both are on the classpath.
 */
@Configuration(proxyBeanMethods = false)
public class OrgCreationLimitConfig {

    /** The open default: one org per install, bootstrapped on first signup. */
    @Bean
    @ConditionalOnMissingBean(OrgCreationLimit.class)
    OrgCreationLimit orgCreationLimit() {
        return () -> 1;
    }
}

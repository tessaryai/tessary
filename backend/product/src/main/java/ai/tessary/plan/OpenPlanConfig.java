// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.ingest.IngestQuotaGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OPEN edition's plan SPI default, in the one shape that actually works.
 *
 * <p><b>Why a {@code @Bean} method rather than {@code @Component} on the implementation.</b> The default has
 * to step aside for a paid bean of the same type, which means {@link ConditionalOnMissingBean} — and that
 * annotation is only sound on a {@code @Bean} method. Put it on a component-scanned class and the class
 * cancels ITSELF: the scanner registers the definition first and evaluates the condition second, so the
 * search finds the very bean it is guarding and declines to register it. That is not a theory. It is what
 * one commit in this epic shipped, and the condition report named it outright —
 * {@code @ConditionalOnMissingBean (types: ai.tessary.ingest.IngestQuotaGate) found beans of type
 * 'ai.tessary.ingest.IngestQuotaGate' uncappedIngestQuotaGate} — leaving the open context with no
 * {@code IngestQuotaGate} at all, so {@code OtlpIngestService} failed to construct and the application did
 * not start in EITHER edition.
 *
 * <p>On a {@code @Bean} method the method's own definition is not yet registered when the condition runs, so
 * the search sees only OTHER candidates — which is exactly the question being asked. Same shape as
 * {@code FeatureFlagsConfig}, which is what this one was meant to copy.
 *
 * <p>The displacement is exercised, as of #881: {@code PaidPlanAutoConfigurationTest} asserts that exactly
 * one {@code IngestQuotaGate} and one {@code FeatureFlags} bean survives and that the paid one is the
 * survivor. It ran over three pairs until #883 deleted the third — the {@code OrgPlanAssignment} SPI, whose
 * last open caller was {@code billing/}. It runs on the PAID side, and it has to: {@code app}'s dependency
 * closure cannot contain a paid module while the {@code enforce-open-to-paid-direction} enforcer stands, so
 * no open test can ever see a paid bean to be displaced by. Paid code reaches a running backend through the
 * {@code AutoConfiguration.imports} in the paid jar, read off the runtime classpath — {@code ai.tessary.paid.*}
 * is outside the scan root and always will be.
 */
@Configuration(proxyBeanMethods = false)
public class OpenPlanConfig {

    /** No cap, so nothing to enforce. Required by {@code OtlpIngestService}'s constructor. */
    @Bean
    @ConditionalOnMissingBean(IngestQuotaGate.class)
    UncappedIngestQuotaGate uncappedIngestQuotaGate() {
        return new UncappedIngestQuotaGate();
    }
}

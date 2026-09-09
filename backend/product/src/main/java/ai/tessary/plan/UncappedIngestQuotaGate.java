// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.ingest.IngestQuotaGate;

/**
 * This build's {@link IngestQuotaGate}: there is no cap, so there is nothing to enforce. The ingest
 * front door admits everything, with no plan tier to read a limit from.
 *
 * <p>{@code OtlpIngestService} takes {@link IngestQuotaGate} as a required constructor parameter, so a
 * Spring context with no bean for it fails to start. Registered by {@code OpenPlanConfig} as a
 * {@code @Bean} with {@code @ConditionalOnMissingBean}, so a build may override this alias with a
 * metered implementation. Deliberately not a {@code @Component}: that conditional on a scanned class
 * would match against a registry that already holds the class itself, canceling the bean and leaving
 * the context with no {@code IngestQuotaGate} at all.
 */
public class UncappedIngestQuotaGate implements IngestQuotaGate {

    @Override
    public void requireIngestWithinQuota(String projectId) {
        // Uncapped: nothing to check, nothing to read, nothing to throw.
    }
}

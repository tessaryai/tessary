// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import ai.tessary.evals.ingest.IngestQuotaGate;

/**
 * The OPEN edition's {@link IngestQuotaGate}: there is no cap, so there is nothing to enforce.
 *
 * <p>Self-hosting is free at any scale (the boundary's own words), which in code means a self-hoster's ingest
 * front door admits everything. There is no plan tier to read a limit from and no reason to do the
 * {@code metric_rollup} read that enforcing one would cost.
 *
 * <p>This bean is not optional decoration. {@code OtlpIngestService} takes {@link IngestQuotaGate} as a
 * REQUIRED constructor parameter, and the only implementation used to be {@code CapabilityIngestQuotaGate},
 * which moved to {@code tessary-paid/plan} with the quota engine. Without this class the open Spring context
 * has no bean for the SPI and does not start — which is exactly the failure open-core epic 1 issue 8 exists to
 * prevent, landed here because issue 1 is what created the gap.
 *
 * <p>Registered by {@code OpenPlanConfig} as a {@code @Bean} with {@code @ConditionalOnMissingBean}, so the
 * metered paid gate displaces it rather than colliding with it. Deliberately NOT a {@code @Component}: that
 * conditional on a scanned class matches against a registry that already holds the class itself, so the bean
 * cancels itself and the context starts with no {@code IngestQuotaGate} at all. See that config's header.
 * The displacement is exercised by {@code PaidPlanAutoConfigurationTest} in {@code tessary-paid/plan} (#881),
 * which is the only side that can hold it: an open test can never depend on the paid jar.
 */
public class UncappedIngestQuotaGate implements IngestQuotaGate {

    @Override
    public void requireIngestWithinQuota(String projectId) {
        // Uncapped. Nothing to check, nothing to read, nothing to throw.
    }
}

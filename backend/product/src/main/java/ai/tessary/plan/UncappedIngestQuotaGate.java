// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.ingest.IngestQuotaGate;

/**
 * This build's {@link IngestQuotaGate}: no cap. Registered by {@code OpenPlanConfig} with
 * {@code @ConditionalOnMissingBean} so a build can override it. Not a {@code @Component}: the conditional on a scanned
 * class would match the class itself and leave no bean.
 */
public class UncappedIngestQuotaGate implements IngestQuotaGate {

    @Override
    public void requireIngestWithinQuota(String projectId) {
    }
}

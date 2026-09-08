// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

/**
 * The gate on ADMITTING ingested spans — {@code ingested_spans_monthly}, the plan quota that meters the
 * front door. It is what makes an exceeded span cap reject a push rather than merely read as {@code
 * exceeded} on the billing page.
 *
 * <p>An interface rather than a direct call because of where the two halves sit: the capability layer
 * ({@code plan/CapabilityService}) lives in {@code product}, and {@code ingest} lives one module BELOW it in
 * {@code substrate}. That is the same layering wall {@code redaction/CustomRuleGate} hit, and the same reason
 * {@code ingested_spans_monthly} shipped computed-but-unenforced: the quota was served to the UI and to
 * billing, and there was no downward call from ingest that could consult it. The repo's convention for an edge
 * that must point up is an interface owned by the lower layer and implemented above ({@code
 * ingest/CallSiteRegistry} is the precedent), which is this.
 *
 * <p><b>Enforcement is period-lagged, deliberately.</b> The quota is compared against {@code metric_rollup},
 * which {@code MeteringWorker} only fills for CLOSED buckets — so an org crossing its cap mid-bucket keeps
 * ingesting until the bucket closes and the next heartbeat meters it. The overshoot is bounded by one hour
 * plus one heartbeat, against a MONTHLY cap. Live-counting {@code span} per export instead would put an
 * unbounded aggregate on the ingest hot path to shave a sub-percent tolerance off a monthly figure, which is
 * the wrong trade; the lag is accepted and stated rather than closed.
 */
public interface IngestQuotaGate {

    /**
     * Throw {@code 402 QUOTA_EXCEEDED} if the project's org has reached its ingested-span cap for the
     * current billing period. Uncapped orgs and unknown projects never throw.
     */
    void requireIngestWithinQuota(String projectId);
}

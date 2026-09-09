// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import ai.tessary.config.IntelligenceProperties;
import ai.tessary.tenant.Ids;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The governed cross-customer priors pipeline. Ties together the storage
 * ({@link PriorContributionRepository}) and the governance core ({@link PriorsGovernance}) into
 * the four operations the boundary needs:
 *
 * <ol>
 *   <li>{@link #optIn}/{@link #revokeConsent} — explicit per-customer opt-in (default: no row =
 *       not pooled).</li>
 *   <li>{@link #contribute} — record one tenant's aggregate-only {@link PriorContribution}. The
 *       typed guard validates it at construction; this only persists. Refused unless the org has
 *       opted in.</li>
 *   <li>{@link #derive} — produce the published {@link DerivedPrior} for a feature bucket, gated
 *       by k-anonymity + DP-ε. Fails <b>open</b> to the per-customer baseline (empty) when pooling
 *       is disabled or the cohort is too small — never throws into a caller's hot path.</li>
 *   <li>{@link #deleteOnChurn} — provably remove a tenant's entire contribution and recompute the
 *       affected priors without it (returns the recomputed priors so the removal is observable).</li>
 * </ol>
 *
 * <p>Everything here is single-process today; the same surface is what a future out-of-process
 * priors service behind the Cloud-intelligence boundary would expose, so moving the store to
 * ClickHouse doesn't touch callers.
 *
 * <p><b>Single-tenant mode.</b> Above the per-feature governance sits a single,
 * top-level tenancy gate ({@link IntelligenceProperties#isSingleTenant()}, default {@code true} —
 * the enterprise-default shape). When single-tenant mode is on, this service is the one provable
 * chokepoint that hard-refuses every cross-tenant operation — {@link #optIn}, {@link #contribute},
 * and {@link #derive} all no-op — <em>regardless</em> of {@code tessary.priors.enabled} or any
 * per-org consent row. That coarse outer gate is the SOC 2 evidence control: an auditor reads one
 * flag (and the boot-time log line below) to conclude no tenant's data can cross the boundary.
 */
@Service
public class PriorsService {

    private static final Logger log = LoggerFactory.getLogger(PriorsService.class);

    private final PriorContributionRepository repo;
    private final PriorsGovernance governance;
    private final IntelligenceProperties intelligence;

    public PriorsService(
            PriorContributionRepository repo, PriorsGovernance governance, IntelligenceProperties intelligence) {
        this.repo = repo;
        this.governance = governance;
        this.intelligence = intelligence;
    }

    /**
     * Emit the tenancy-mode decision once at boot so it lands in the audit log — the SOC 2 evidence
     * artifact that records, per deployment start, whether cross-customer pooling was even reachable.
     */
    @PostConstruct
    void logTenancyMode() {
        if (intelligence.isSingleTenant()) {
            log.info("priors: SINGLE-TENANT intelligence mode is ON (tessary.intelligence-mode.single-tenant=true) "
                    + "— cross-customer pooling is hard-disabled; no contribution is pooled and no prior "
                    + "crosses a tenant boundary regardless of tessary.priors.enabled");
        } else {
            log.warn(
                    "priors: single-tenant intelligence mode is OFF (tessary.intelligence-mode.single-tenant=false) "
                            + "— governed cross-customer pooling MAY run (still gated by tessary.priors.enabled={}, "
                            + "per-org consent, k-anonymity and DP-epsilon)",
                    governance.poolingEnabled());
        }
    }

    /**
     * Whether cross-customer pooling is reachable at all. {@code false} whenever single-tenant mode
     * is on — the top-level single-tenant gate that wins over {@code tessary.priors.enabled} and every consent
     * row. Exposed so callers/diagnostics can assert the tenancy posture without reaching into
     * config.
     */
    public boolean crossCustomerPoolingPermitted() {
        return !intelligence.isSingleTenant();
    }

    /**
     * The <b>no-shared-model-training guarantee</b>, expressed as a first-class, assertable
     * posture rather than an emergent property. Always {@code true}: <em>this platform runs no
     * fine-tuning or model-training pipeline on customer data</em>, and the priors pipeline this
     * service owns has no path that could feed one.
     *
     * <p>This is a guarantee about the <em>absence of a training sink</em> — distinct from the
     * cross-customer <em>pooling</em> claim in {@link #crossCustomerPoolingPermitted()} (and from
     * the three-data-classes proof that raw data never crosses). The only two cross-tenant types
     * are {@link PriorContribution} and {@link DerivedPrior}, both <b>aggregate-only by
     * construction</b> — no field can hold a span body, prompt, or model output (proven structurally
     * in {@code PriorContributionGuardTest}). The only sinks reachable from this service are (a) the
     * platform's own {@code prior_contribution} store via {@link PriorContributionRepository} and
     * (b) the {@link DerivedPrior} it returns to callers — neither is a model-training input. There is
     * deliberately <b>no training / fine-tune sink type anywhere in the codebase</b> for customer data
     * to flow into; that absence is the DPA "no-train" clause encoded as architecture.
     *
     * <p>Unlike {@link #crossCustomerPoolingPermitted()} this has no legitimate {@code false} state —
     * it is an unconditional architectural property, so it is a constant rather than a config knob.
     * Customer data sent to an <em>inference</em> provider (Bedrock) for evaluation is
     * covered by that provider's no-train data-handling terms — an <em>external contract</em>
     * documented in {@code devdocs/reference/principles.md} § "Product &amp; positioning", not
     * asserted here, because this method only speaks to what the codebase itself controls. The DPA carve-out is narrow and consented: only the
     * aggregate {@link DerivedPrior} crosses, and only for an org that has explicitly {@link #optIn}'d.
     *
     * <p>Exposed so diagnostics, a procurement review, and
     * {@code NoSharedModelTrainingGuaranteeTest} can assert the posture without reaching into config.
     *
     * @return {@code true}, always — no code path trains or fine-tunes a model on customer data
     */
    public boolean noSharedModelTrainingPermitted() {
        // Intentionally a constant. The absence of any training / fine-tune sink for customer data is
        // an unconditional property of this codebase, not a runtime mode. If a future change ever
        // introduces such a sink, NoSharedModelTrainingGuaranteeTest must be revisited deliberately —
        // it is the tripwire that keeps this guarantee honest.
        return true;
    }

    /**
     * Record explicit opt-in for an org. Idempotent. <b>Refused (no-op) in single-tenant mode</b> —
     * an org cannot consent to pooling on a deployment that does not permit pooling at all.
     */
    public void optIn(String orgId) {
        if (intelligence.isSingleTenant()) {
            log.warn(
                    "priors: refusing opt-in for org={} — single-tenant intelligence mode is on "
                            + "(no cross-customer pooling on this deployment)",
                    orgId);
            return;
        }
        repo.optIn(orgId, Instant.now().toString());
        log.info("priors: org={} opted in to cross-customer pooling", orgId);
    }

    /** Revoke pooling consent (excludes the org from future reads; does NOT delete its data —
     *  use {@link #deleteOnChurn} for the full removal). */
    public void revokeConsent(String orgId) {
        repo.revoke(orgId);
        log.info("priors: org={} revoked pooling consent", orgId);
    }

    /**
     * Record one aggregate-only contribution. Refused (no-op, false) unless the org has opted in —
     * a tenant's data is never pooled without explicit consent, even when pooling is enabled
     * globally. The {@link PriorContribution} type has already guaranteed the payload is
     * aggregate-only; we only persist it.
     *
     * @return true if persisted; false if the org has not consented or single-tenant mode is on
     */
    public boolean contribute(PriorContribution contribution) {
        if (intelligence.isSingleTenant()) {
            log.debug(
                    "priors: dropping contribution from org={} — single-tenant intelligence mode is on",
                    contribution.orgId());
            return false;
        }
        if (!repo.hasConsent(contribution.orgId())) {
            log.debug("priors: dropping contribution from non-consenting org={}", contribution.orgId());
            return false;
        }
        repo.upsert(contribution, Instant.now().toString());
        return true;
    }

    /** Build a contribution id + persist in one call (convenience for producers). */
    public boolean contribute(
            String orgId, String featureKey, double value, long sampleCount, @Nullable String contentRef) {
        return contribute(new PriorContribution(Ids.ulid(), orgId, featureKey, value, sampleCount, contentRef));
    }

    /**
     * The published prior for a feature bucket, or {@link Optional#empty()} (fail-open to the
     * per-customer baseline) when single-tenant mode is on, pooling is disabled, or the
     * k-anonymity gate suppresses it. Never throws — a priors miss must degrade to the single-tenant
     * path, not break the caller.
     */
    public Optional<DerivedPrior> derive(String featureKey) {
        if (intelligence.isSingleTenant() || !governance.poolingEnabled()) {
            // Single-tenant mode hard-disables pooling above the per-feature governance gate.
            return Optional.empty();
        }
        try {
            List<PriorContribution> cohort = repo.consentedContributionsFor(featureKey);
            return governance.deriveFor(featureKey, cohort);
        } catch (RuntimeException e) {
            // Fail open: a priors failure must never break the per-customer baseline path.
            log.warn("priors: derive failed for feature={}, falling open to baseline", featureKey, e);
            return Optional.empty();
        }
    }

    /**
     * Churn-deletion: remove the org's entire pool footprint and recompute the priors for every
     * feature bucket it had touched, <em>without</em> it. Returns the recomputed priors keyed by
     * the feature buckets the churned org had contributed to — the caller can assert the org's
     * influence is gone (a bucket that drops below {@code minCohort} after removal returns empty).
     *
     * @return for each feature the churned org had contributed to, the prior recomputed without it
     */
    @Transactional
    public List<RecomputedPrior> deleteOnChurn(String orgId) {
        List<String> touchedFeatures = repo.findByOrg(orgId).stream()
                .map(PriorContribution::featureKey)
                .distinct()
                .toList();

        int removed = repo.deleteByOrg(orgId);
        log.info(
                "priors: churn-deletion org={} removed {} contribution(s) across {} feature(s)",
                orgId,
                removed,
                touchedFeatures.size());

        return touchedFeatures.stream()
                .map(f -> new RecomputedPrior(f, derive(f)))
                .toList();
    }

    /** A feature bucket recomputed after a churn deletion; {@code prior} is empty if the bucket
     *  fell below the k-anonymity floor once the churned org was removed. */
    public record RecomputedPrior(String featureKey, Optional<DerivedPrior> prior) {}
}

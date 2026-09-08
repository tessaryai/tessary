// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.priors;

import org.jspecify.annotations.Nullable;

/**
 * One consenting tenant's <b>aggregate-only</b> contribution to the cross-customer priors pool —
 * the second of the three data classes (the only class that carries tenant-derived <em>numbers</em>
 * across the pooling boundary). It is a deliberately narrow, content-free type so that raw tenant
 * data is <em>structurally</em> unable to cross: there is no field that can hold a span body, a
 * trace I/O, a prompt, a model output, or any free text. Every numeric field is an aggregate over
 * many of the org's own observations.
 *
 * <p><b>The three data classes, encoded as types:</b>
 * <ol>
 *   <li><b>Definitions</b> may cross freely (grader specs, rubric craft) — not modelled here; they
 *       are tenant-agnostic by construction.</li>
 *   <li><b>Raw data</b> is pinned / refs-only and <em>never</em> crosses — enforced by the absence
 *       of any content field on this type. A {@code contentRef} may carry an opaque pin (e.g. a
 *       content-address or row id <em>within the owning tenant</em>) for provenance, but it is
 *       validated to be a ref, never a payload (see {@link #validate}).</li>
 *   <li><b>Derived priors</b> are aggregate-only — this type and {@link DerivedPrior}.</li>
 * </ol>
 *
 * <p>Construct via the canonical constructor; it validates the invariants and throws
 * {@link IllegalArgumentException} on any violation, so an ill-formed contribution can never be
 * persisted or pooled. {@code orgId} is the cohort key (k-anonymity) and the deletion key
 * (churn): a tenant's entire footprint in the pool is {@code WHERE org_id = ?}.
 *
 * @param contributionId opaque id of this contribution row
 * @param orgId the contributing tenant (cohort key + churn-deletion key); never the raw data
 * @param featureKey the named aggregate feature bucket (e.g. {@code grader.pass_rate};
 *     {@code observer.drift_rate}). Buckets, not free text — see {@link #validate}.
 * @param value the aggregate value, bounded to {@code [0, 1]} (a rate/fraction). Bounding is what
 *     makes DP sensitivity well-defined.
 * @param sampleCount how many of the org's own observations this aggregate was computed over
 *     ({@code > 0}); recorded for provenance and k-anonymity cohort accounting, and used to gate
 *     thin contributions — deliberately <em>not</em> a weight on the pooled mean (the published
 *     statistic is an unweighted per-org mean — see {@link PriorsGovernance}).
 * @param contentRef optional opaque, tenant-internal provenance pin (NOT content). Bounded length,
 *     no newlines — a ref, never a payload.
 */
public record PriorContribution(
        String contributionId,
        String orgId,
        String featureKey,
        double value,
        long sampleCount,
        @Nullable String contentRef) {

    /** Max length of a provenance ref; a real payload would blow past this. */
    public static final int MAX_REF_LEN = 128;

    /** Allowed feature-bucket shape: {@code namespace.metric}, lowercase, no free text. */
    private static final java.util.regex.Pattern FEATURE_KEY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    public PriorContribution {
        validate(contributionId, orgId, featureKey, value, sampleCount, contentRef);
    }

    private static void validate(
            String contributionId,
            String orgId,
            String featureKey,
            double value,
            long sampleCount,
            @Nullable String contentRef) {
        require(contributionId != null && !contributionId.isBlank(), "contributionId is required");
        require(orgId != null && !orgId.isBlank(), "orgId is required");
        require(
                featureKey != null && FEATURE_KEY.matcher(featureKey).matches(),
                "featureKey must match namespace.metric (lowercase, no free text): " + featureKey);
        require(
                Double.isFinite(value) && value >= 0.0 && value <= 1.0,
                "value must be a finite rate in [0,1]: " + value);
        require(sampleCount > 0, "sampleCount must be positive: " + sampleCount);
        if (contentRef != null) {
            // A ref carries provenance, never content: bound the length and forbid newlines so a
            // raw span/trace/prompt body cannot be smuggled across the boundary in this field.
            require(
                    contentRef.length() <= MAX_REF_LEN,
                    "contentRef exceeds " + MAX_REF_LEN + " chars — looks like content, not a ref");
            require(
                    contentRef.indexOf('\n') < 0 && contentRef.indexOf('\r') < 0,
                    "contentRef must be a single-line ref, not multi-line content");
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException(msg);
        }
    }
}

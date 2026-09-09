// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

/**
 * A published cross-customer prior — the third data class: <b>aggregate-only</b>, with
 * differential-privacy noise already applied and the k-anonymity cohort gate already passed. This
 * is the only priors artifact the OSS core / intelligence features ever read; it is fully
 * detached from any single tenant's raw data.
 *
 * <p>By construction a {@code DerivedPrior} carries no tenant identity and no content — only the
 * feature bucket, the noised pooled value, and the cohort size that proves the k-anonymity gate
 * was satisfied. {@link #cohortSize} is published (not the contributors) so a consumer can reason
 * about confidence without learning <em>who</em> contributed.
 *
 * @param featureKey the aggregate feature bucket this prior is for
 * @param value the DP-noised, sample-weighted pooled aggregate, clamped back to {@code [0,1]}
 * @param cohortSize number of distinct consenting orgs that contributed (proves {@code >= minCohort})
 * @param epsilon the DP budget spent producing this value (for auditability)
 */
public record DerivedPrior(String featureKey, double value, int cohortSize, double epsilon) {

    public DerivedPrior {
        if (featureKey == null || featureKey.isBlank()) {
            throw new IllegalArgumentException("featureKey is required");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite: " + value);
        }
        if (cohortSize < 0) {
            throw new IllegalArgumentException("cohortSize must be non-negative: " + cohortSize);
        }
        if (epsilon <= 0 || Double.isNaN(epsilon)) {
            throw new IllegalArgumentException("epsilon must be positive: " + epsilon);
        }
    }
}

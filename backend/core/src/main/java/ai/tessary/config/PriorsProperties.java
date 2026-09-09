// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cross-customer priors governance, bound from {@code tessary.priors.*}. <b>Disabled by
 * default</b> so the platform is single-tenant per-customer out of the box: no contribution is
 * pooled and no prior crosses a tenant boundary until a deployment explicitly opts in
 * ({@code tessary.priors.enabled=true}) <em>and</em> each contributing org opts in individually
 * (a row in {@code prior_consent}). Mirrors {@link SynthProperties}/{@link ObserverProperties}
 * in shape (default-off, knob-per-governance-lever).
 *
 * <p>The two governance levers — {@link #minCohort} (k-anonymity) and {@link #epsilon}
 * (differential-privacy budget) — are the load-bearing knobs the no-train guarantee calls
 * out: a derived prior is only emitted when at least {@code minCohort} distinct orgs contributed
 * to its bucket, and the published unweighted per-org mean carries calibrated Laplace noise scaled
 * by {@code 1/(cohortSize * epsilon)}. Lowering {@code minCohort} or raising {@code epsilon} weakens
 * the privacy guarantee; both default to conservative values and are validated at boot.
 */
@Component
@ConfigurationProperties(prefix = "tessary.priors")
public class PriorsProperties {

    /**
     * Master switch. {@code false} (default) → fully single-tenant: the
     * {@link ai.tessary.priors.PriorsService} refuses to publish any pooled prior and
     * every read falls open to the per-customer baseline. Even when {@code true}, an org's
     * contribution is only pooled if that org has an explicit {@code prior_consent} opt-in.
     */
    private boolean enabled = false;

    /**
     * k-anonymity minimum cohort size. A derived prior for a feature bucket is published only
     * when {@code >= minCohort} <em>distinct consenting orgs</em> contributed to it; otherwise
     * the bucket is suppressed (no prior crosses the boundary). Must be {@code >= 2} — a cohort
     * of one is just that one tenant's raw aggregate, which would leak across the boundary.
     */
    private int minCohort = 5;

    /**
     * Differential-privacy epsilon (the privacy budget). Laplace noise with scale
     * {@code sensitivity / (cohortSize * epsilon)} is added to each published aggregate. Smaller
     * epsilon → more noise → stronger privacy. Must be {@code > 0}.
     */
    private double epsilon = 1.0;

    /**
     * The L1 sensitivity numerator for the DP noise. The published statistic is the
     * <em>unweighted</em> mean of the per-org bounded-{@code [0,1]} aggregates, whose true L1
     * sensitivity is {@code 1/cohortSize}; the governance layer divides this numerator by cohort
     * size at publish time, so the default {@code 1.0} (the full {@code [0,1]} range) yields the
     * standard, correctly-calibrated Laplace scale {@code 1 / (cohortSize * epsilon)}. Must be
     * {@code > 0}.
     */
    private double sensitivity = 1.0;

    /**
     * Fail-fast at boot on a misconfigured privacy boundary: {@code minCohort < 2} would publish a
     * cohort-of-one org's aggregate across the tenant boundary, defeating k-anonymity, and a
     * non-positive {@code epsilon}/{@code sensitivity} makes the DP noise undefined. Refusing to
     * start is safer than silently weakening the guarantee.
     */
    @PostConstruct
    void validate() {
        if (minCohort < 2) {
            throw new IllegalStateException("tessary.priors.min-cohort must be >= 2 (a cohort of one leaks a single "
                    + "tenant's aggregate across the boundary); got " + minCohort);
        }
        if (epsilon <= 0.0 || Double.isNaN(epsilon)) {
            throw new IllegalStateException(
                    "tessary.priors.epsilon must be > 0 (it is the DP privacy budget); got " + epsilon);
        }
        if (sensitivity <= 0.0 || Double.isNaN(sensitivity)) {
            throw new IllegalStateException(
                    "tessary.priors.sensitivity must be > 0 (it scales the DP noise); got " + sensitivity);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public int getMinCohort() {
        return minCohort;
    }

    public void setMinCohort(int v) {
        this.minCohort = v;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public void setEpsilon(double v) {
        this.epsilon = v;
    }

    public double getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(double v) {
        this.sensitivity = v;
    }
}

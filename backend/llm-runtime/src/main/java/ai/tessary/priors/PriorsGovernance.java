// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import ai.tessary.config.PriorsProperties;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The governance core for cross-customer priors: the pure, deterministic-given-its-RNG functions
 * that turn a set of per-tenant {@link PriorContribution}s into either a published
 * {@link DerivedPrior} or a suppression. Kept free of I/O so the two load-bearing invariants —
 * the <b>k-anonymity minimum-cohort gate</b> and the <b>differential-privacy ε-noise</b> — are
 * unit-testable without a database.
 *
 * <ul>
 *   <li><b>k-anonymity:</b> {@link #deriveFor} returns empty unless the contributions span at
 *       least {@code minCohort} <em>distinct orgs</em>. Distinct orgs, not rows: ten contributions
 *       from one tenant are a cohort of one and are suppressed.</li>
 *   <li><b>DP-ε:</b> the pooled statistic is the <b>unweighted mean of the per-org aggregates</b>
 *       (one value per org — the {@code (org_id, feature_key)} unique constraint guarantees one
 *       contribution per org per bucket), perturbed with Laplace noise of scale
 *       {@code sensitivity / (cohortSize * epsilon)}, then clamped back to {@code [0,1]}. For an
 *       <em>unweighted</em> mean of {@code n} bounded-{@code [0,1]} per-org values, one org's L1
 *       sensitivity is provably {@code Δf = 1/n}; with {@code sensitivity = 1} (the full {@code [0,1]}
 *       range) the scale {@code 1 / (cohortSize * epsilon)} is the standard, correctly-calibrated
 *       Laplace mechanism. (A sample-<em>weighted</em> mean was rejected: with unbounded
 *       {@code sampleCount} a single heavy contributor's L1 sensitivity approaches the full range,
 *       so dividing by cohort size would under-noise and weaken the ε-DP guarantee.)</li>
 * </ul>
 */
@Component
public class PriorsGovernance {

    private final PriorsProperties props;

    public PriorsGovernance(PriorsProperties props) {
        this.props = props;
    }

    /**
     * Derive the published prior for one feature bucket from its contributions, or
     * {@link Optional#empty()} if the k-anonymity gate suppresses it. {@code rng} is injected so
     * tests can pin the Laplace draw; production passes a fresh {@link Random}.
     */
    public Optional<DerivedPrior> deriveFor(String featureKey, List<PriorContribution> contributions, Random rng) {
        Set<String> orgs = distinctOrgs(contributions);
        int cohortSize = orgs.size();
        if (cohortSize < props.getMinCohort()) {
            // k-anonymity gate: too few distinct tenants — nothing crosses the boundary.
            return Optional.empty();
        }

        double perOrgMean = unweightedPerOrgMean(contributions);
        double noised = addLaplaceNoise(perOrgMean, cohortSize, rng);
        double clamped = Math.max(0.0, Math.min(1.0, noised));
        return Optional.of(new DerivedPrior(featureKey, clamped, cohortSize, props.getEpsilon()));
    }

    /** Distinct contributing orgs — the cohort whose size the k-anonymity gate checks. */
    public static Set<String> distinctOrgs(List<PriorContribution> contributions) {
        return contributions.stream().map(PriorContribution::orgId).collect(Collectors.toSet());
    }

    /**
     * <b>Unweighted</b> mean of the per-org (already bounded-{@code [0,1]}) aggregate values — one
     * value per distinct org. This is the published statistic, chosen so DP sensitivity is sound:
     * for an unweighted mean of {@code n} bounded values, a single org's L1 sensitivity is exactly
     * {@code 1/n}, which justifies dividing the Laplace scale by {@code cohortSize}. ({@code
     * sampleCount} is deliberately <em>not</em> a weight here — weighting by an unbounded sample
     * count would let one heavy contributor dominate the mean, breaking the {@code 1/n} bound.)
     *
     * <p>Storage already enforces one row per {@code (org, feature)}; we additionally collapse by
     * {@code orgId} here so the statistic stays a true per-org mean even if a caller passes a list
     * with multiple rows for an org (each org first averaged to a single value, then averaged
     * across orgs).
     */
    static double unweightedPerOrgMean(List<PriorContribution> contributions) {
        Map<String, double[]> perOrg = new HashMap<>(); // org -> {sum, count}
        for (PriorContribution c : contributions) {
            double[] acc = perOrg.computeIfAbsent(c.orgId(), k -> new double[2]);
            acc[0] += c.value();
            acc[1] += 1.0;
        }
        if (perOrg.isEmpty()) {
            return 0.0;
        }
        double sumOfOrgMeans = 0.0;
        for (double[] acc : perOrg.values()) {
            sumOfOrgMeans += acc[0] / acc[1];
        }
        return sumOfOrgMeans / perOrg.size();
    }

    /**
     * Add Laplace(0, b) noise with {@code b = sensitivity / (cohortSize * epsilon)}. The statistic
     * is the unweighted per-org mean, whose L1 sensitivity is {@code 1/cohortSize}; with {@code
     * sensitivity = 1} this is the standard, correctly-calibrated Laplace mechanism. Uses the
     * inverse-CDF method on a uniform draw so the distribution is exact and the test can pin it
     * with a seeded {@link Random}.
     */
    double addLaplaceNoise(double value, int cohortSize, Random rng) {
        double scale = props.getSensitivity() / (cohortSize * props.getEpsilon());
        return value + sampleLaplace(scale, rng);
    }

    /**
     * One Laplace(0, scale) draw via inverse transform. {@code Random.nextDouble()} returns the
     * half-open {@code [0, 1)}, so {@code u = 0.5 - nextDouble()} lands in {@code (-0.5, 0.5]}. The
     * inverse-CDF needs {@code log(1 - 2|u|)}, whose argument is {@code 0} only at the single point
     * {@code u = -0.5} (when {@code nextDouble()} returns exactly {@code 0.0}); we floor that
     * argument at the smallest positive double so {@code log} never sees {@code 0} → {@code
     * -Infinity}. The floor shifts at most one astronomically-rare draw by a bounded amount and
     * avoids a float-equality branch.
     */
    static double sampleLaplace(double scale, Random rng) {
        double u = 0.5 - rng.nextDouble(); // (-0.5, 0.5]
        double arg = Math.max(1.0 - 2.0 * Math.abs(u), Double.MIN_VALUE); // floor away from log(0)
        return -scale * Math.signum(u) * Math.log(arg);
    }

    /** Whether pooling is switched on at the deployment level (still gated per-org by consent). */
    public boolean poolingEnabled() {
        return props.isEnabled();
    }

    /** The configured k for k-anonymity (exposed for callers/logging). */
    public int minCohort() {
        return props.getMinCohort();
    }

    /**
     * Convenience: derive with a fresh RNG (production callers that don't pin the draw).
     *
     * <p>{@link SecureRandom}, not {@code new Random()}: the draw this seeds is the DP-ε Laplace
     * noise, and a differential-privacy guarantee is only as strong as the unpredictability of the
     * noise. {@code java.util.Random} is a 48-bit LCG whose stream is recoverable from a couple of
     * outputs, so an observer who can see enough published priors could estimate the noise and
     * subtract it back off, which is exactly the attack the mechanism exists to prevent. The seeded
     * overload is unchanged and is what the tests pin.
     */
    public Optional<DerivedPrior> deriveFor(String featureKey, List<PriorContribution> contributions) {
        return deriveFor(featureKey, contributions, new SecureRandom());
    }

    @Nullable
    PriorsProperties propsForTest() {
        return props;
    }
}

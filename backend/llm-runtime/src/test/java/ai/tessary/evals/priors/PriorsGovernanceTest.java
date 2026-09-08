// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.priors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.config.PriorsProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The two governance invariants, proven in isolation (no DB): the <b>k-anonymity minimum-cohort
 * gate</b> suppresses priors from too few distinct tenants, and <b>DP-ε Laplace noise</b> is
 * actually applied to the published aggregate. Pure unit tests over {@link PriorsGovernance}.
 */
class PriorsGovernanceTest {

    private static PriorsProperties props(int minCohort, double epsilon) {
        PriorsProperties p = new PriorsProperties();
        p.setEnabled(true);
        p.setMinCohort(minCohort);
        p.setEpsilon(epsilon);
        p.setSensitivity(1.0);
        return p;
    }

    private static PriorContribution from(String org, double value, long samples) {
        return new PriorContribution(org + "-c", org, "grader.pass_rate", value, samples, null);
    }

    @Test
    void kAnonGate_suppressesACohortBelowK() {
        PriorsGovernance gov = new PriorsGovernance(props(3, 1.0));
        // Only 2 distinct orgs (org-a appears twice but distinct-orgs counts it once).
        List<PriorContribution> cohort = List.of(from("a", 0.8, 10), from("b", 0.6, 10));
        assertTrue(
                gov.deriveFor("grader.pass_rate", cohort, new Random(1)).isEmpty(),
                "cohort of 2 distinct orgs must be suppressed when minCohort=3");
    }

    @Test
    void kAnonGate_countsDistinctOrgsNotRows() {
        PriorsGovernance gov = new PriorsGovernance(props(3, 1.0));
        // Three ROWS but only ONE org — still a cohort of one, must be suppressed.
        List<PriorContribution> sameOrg = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sameOrg.add(new PriorContribution("a-c" + i, "a", "grader.pass_rate", 0.5, 10, null));
        }
        // (Distinct-orgs is what the gate measures; rows-from-one-org is a cohort of one.)
        assertEquals(1, PriorsGovernance.distinctOrgs(sameOrg).size());
    }

    @Test
    void kAnonGate_publishesAtOrAboveK() {
        PriorsGovernance gov = new PriorsGovernance(props(3, 1.0));
        List<PriorContribution> cohort = List.of(from("a", 0.8, 10), from("b", 0.6, 10), from("c", 0.7, 10));
        Optional<DerivedPrior> prior = gov.deriveFor("grader.pass_rate", cohort, new Random(1));
        assertTrue(prior.isPresent(), "cohort of 3 distinct orgs must publish when minCohort=3");
        assertEquals(3, prior.get().cohortSize());
        assertEquals("grader.pass_rate", prior.get().featureKey());
    }

    @Test
    void publishedStatisticIsUnweighted_soOneHeavyContributorCannotDominate() {
        // This is the load-bearing DP property: the statistic is the UNWEIGHTED per-org mean, so a
        // single org with a huge sampleCount cannot drag the published value toward its own value.
        // Were it sample-weighted, org-a (1_000_000 samples at 1.0) would pull the mean to ~1.0 and
        // its true L1 sensitivity would approach 1, breaking the 1/n calibration. Unweighted, a
        // dominant-sample org and a thin org each count once: mean of {1.0, 0.0} == 0.5.
        List<PriorContribution> cohort = List.of(from("a", 1.0, 1_000_000), from("b", 0.0, 1));
        assertEquals(
                0.5,
                PriorsGovernance.unweightedPerOrgMean(cohort),
                1e-12,
                "per-org mean must ignore sampleCount weighting — each org counts exactly once");
    }

    @Test
    void perOrgMean_collapsesMultipleRowsPerOrgToOneVote() {
        // Defensive: even if a caller passes multiple rows for one org, that org still contributes a
        // single per-org value (its own average), preserving the 1/n sensitivity bound.
        List<PriorContribution> cohort = List.of(from("a", 0.0, 10), from("a", 1.0, 10), from("b", 0.5, 10));
        // org-a averages to 0.5, org-b is 0.5 → overall 0.5 (not 0.375 a flat row-mean would give).
        assertEquals(0.5, PriorsGovernance.unweightedPerOrgMean(cohort), 1e-12);
    }

    @Test
    void dpNoise_perturbsTheAggregate_andDiffersByDraw() {
        PriorsGovernance gov = new PriorsGovernance(props(2, 1.0));
        List<PriorContribution> cohort = List.of(from("a", 0.5, 10), from("b", 0.5, 10));
        double cleanMean = PriorsGovernance.unweightedPerOrgMean(cohort); // exactly 0.5

        // Two different RNG seeds → two different noised values, neither (almost surely) the clean mean.
        double v1 = gov.deriveFor("grader.pass_rate", cohort, new Random(1))
                .orElseThrow()
                .value();
        double v2 = gov.deriveFor("grader.pass_rate", cohort, new Random(2))
                .orElseThrow()
                .value();

        assertNotEquals(v1, v2, "different DP draws must yield different published values");
        assertFalse(v1 == cleanMean && v2 == cleanMean, "DP noise must perturb the clean mean");
        assertTrue(v1 >= 0.0 && v1 <= 1.0 && v2 >= 0.0 && v2 <= 1.0, "noised value stays clamped to [0,1]");
    }

    @Test
    void dpNoise_smallerEpsilonMeansMoreNoise() {
        // Over many draws, the mean absolute deviation from the clean value grows as epsilon shrinks.
        List<PriorContribution> cohort = List.of(from("a", 0.5, 10), from("b", 0.5, 10));

        double madTight = meanAbsDev(props(2, 4.0), cohort); // large epsilon → little noise
        double madLoose = meanAbsDev(props(2, 0.25), cohort); // small epsilon → lots of noise

        assertTrue(
                madLoose > madTight,
                "smaller epsilon must produce more noise (madLoose=" + madLoose + " madTight=" + madTight + ")");
    }

    private static double meanAbsDev(PriorsProperties p, List<PriorContribution> cohort) {
        PriorsGovernance gov = new PriorsGovernance(p);
        Random rng = new Random(42);
        double clean = PriorsGovernance.unweightedPerOrgMean(cohort);
        double sum = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            sum += Math.abs(
                    gov.deriveFor("grader.pass_rate", cohort, rng).orElseThrow().value() - clean);
        }
        return sum / n;
    }
}

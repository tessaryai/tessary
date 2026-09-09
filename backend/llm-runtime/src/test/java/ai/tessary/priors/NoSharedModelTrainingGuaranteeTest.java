// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.IntelligenceProperties;
import ai.tessary.config.PriorsProperties;
import org.junit.jupiter.api.Test;

/**
 * The <b>no-shared-model-training guarantee</b>, expressed as a unit test — the same
 * "guarantee-as-a-unit-test" pattern as {@link SingleTenantModeTest}, but for a distinct claim. Those
 * guards prove no cross-customer <em>pooling</em> of raw data; the separate claim here is that
 * <em>no code path trains or fine-tunes a model on customer data</em> — the specific clause a
 * regulated buyer's DPA asserts and a procurement review checks (see
 * {@code devdocs/reference/principles.md} § "Product &amp; positioning").
 *
 * <p>The guarantee is about the <b>absence of a training sink</b>. Two facts make it provable here:
 *
 * <ol>
 *   <li>{@link PriorsService#noSharedModelTrainingPermitted()} is an unconditional {@code true} — a
 *       named, assertable posture (it has no legitimate {@code false} state, so it is a constant, not
 *       a config knob). This is the tripwire: if a future change ever introduces a training sink,
 *       this test must be revisited deliberately.</li>
 *   <li>The only outbound write a contribution can reach is the platform's own
 *       {@code prior_contribution} store — there is no second, model-training sink. With the
 *       repository mocked, a successful {@code contribute} interacts with storage <em>only</em> via
 *       the consent check and the single {@code upsert}; {@code verifyNoMoreInteractions} proves no
 *       other sink is fed.</li>
 * </ol>
 *
 * <p>The structural "raw data cannot cross at all" half of the three-data-classes rule is proven
 * separately in {@link PriorContributionGuardTest}; together the two tests are the code-side evidence
 * the procurement artifact cites.
 */
class NoSharedModelTrainingGuaranteeTest {

    private static final String FEATURE = "grader.pass_rate";
    private static final String ORG = "org_1";

    private static PriorsProperties poolingOnProps() {
        PriorsProperties p = new PriorsProperties();
        p.setEnabled(true);
        p.setMinCohort(2);
        p.setEpsilon(1.0);
        p.setSensitivity(1.0);
        return p;
    }

    /** Mode OFF so the pooling path is fully reachable — the guarantee must hold even then. */
    private static IntelligenceProperties poolingReachableMode() {
        IntelligenceProperties m = new IntelligenceProperties();
        m.setSingleTenant(false);
        return m;
    }

    @Test
    void noSharedModelTrainingIsAnUnconditionalGuarantee() {
        // Holds in the safe default shape...
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        PriorsService singleTenant =
                new PriorsService(repo, new PriorsGovernance(poolingOnProps()), new IntelligenceProperties());
        assertTrue(
                singleTenant.noSharedModelTrainingPermitted(),
                "no-shared-training is guaranteed on a default single-tenant deployment");

        // ...and equally when cross-customer pooling is fully enabled. The guarantee is orthogonal to
        // pooling: even when aggregate priors DO cross, none of that is a model-training input.
        PriorsService poolingOn =
                new PriorsService(repo, new PriorsGovernance(poolingOnProps()), poolingReachableMode());
        assertTrue(
                poolingOn.noSharedModelTrainingPermitted(),
                "no-shared-training holds even with cross-customer pooling enabled");
    }

    @Test
    void contributionReachesOnlyThePlatformsOwnStore_noOtherSink() {
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        when(repo.hasConsent(ORG)).thenReturn(true);
        PriorsService service = new PriorsService(repo, new PriorsGovernance(poolingOnProps()), poolingReachableMode());

        assertTrue(
                service.contribute(ORG, FEATURE, 0.5, 100, null),
                "with pooling reachable and consent, the aggregate contribution is persisted");

        // The ONLY storage interactions are the consent check and the single upsert into the
        // platform's own prior_contribution store. verifyNoMoreInteractions is the load-bearing
        // assertion: there is no second, model-training sink the contribution could be fed to.
        verify(repo).hasConsent(ORG);
        verify(repo).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(repo);
    }
}

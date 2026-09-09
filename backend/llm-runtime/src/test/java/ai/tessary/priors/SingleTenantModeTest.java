// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.IntelligenceProperties;
import ai.tessary.config.PriorsProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves the single-tenant intelligence gate is a real, provable kill-switch — no database
 * needed. The repository is mocked, so any cross-tenant operation that reached storage would show
 * up as an interaction; in single-tenant mode there must be none.
 *
 * <p>The load-bearing claim: single-tenant mode wins over <em>everything</em> downstream. Even with
 * {@code tessary.priors.enabled=true} (pooling feature on) AND the repo reporting consent for the org
 * AND a cohort that would otherwise publish, the service still pools nothing and derives nothing.
 * That is the SOC 2 "provably zero cross-customer pooling" property expressed in code.
 */
class SingleTenantModeTest {

    private static final String FEATURE = "grader.pass_rate";
    private static final String ORG = "org_1";

    private static PriorsProperties poolingOnProps() {
        PriorsProperties p = new PriorsProperties();
        p.setEnabled(true); // pooling feature ON — single-tenant mode must still win
        p.setMinCohort(2);
        p.setEpsilon(1.0);
        p.setSensitivity(1.0);
        return p;
    }

    private static IntelligenceProperties mode(boolean singleTenant) {
        IntelligenceProperties m = new IntelligenceProperties();
        m.setSingleTenant(singleTenant);
        return m;
    }

    @Test
    void singleTenantIsTheDefault() {
        // The enterprise-default shape: no configuration → single-tenant, pooling not permitted.
        assertTrue(new IntelligenceProperties().isSingleTenant());

        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        PriorsService service =
                new PriorsService(repo, new PriorsGovernance(poolingOnProps()), new IntelligenceProperties());
        assertFalse(service.crossCustomerPoolingPermitted(), "default deployment must be single-tenant");
    }

    @Test
    void singleTenantMode_refusesOptIn_andNeverTouchesStorage() {
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        PriorsService service = new PriorsService(repo, new PriorsGovernance(poolingOnProps()), mode(true));

        service.optIn(ORG);

        verifyNoInteractions(repo); // no consent row is ever written in single-tenant mode
    }

    @Test
    void singleTenantMode_dropsContribution_evenWhenRepoWouldConsent() {
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        // Repo would say "yes, consented" — single-tenant mode must short-circuit before it is asked.
        lenient().when(repo.hasConsent(ORG)).thenReturn(true);
        PriorsService service = new PriorsService(repo, new PriorsGovernance(poolingOnProps()), mode(true));

        assertFalse(
                service.contribute(ORG, FEATURE, 0.5, 100, null), "no contribution is pooled in single-tenant mode");

        verify(repo, never()).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repo, never()).hasConsent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void singleTenantMode_derivesNothing_evenWithAPublishableCohort() {
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        // A cohort that WOULD publish (>= minCohort distinct orgs) if it were ever read.
        lenient()
                .when(repo.consentedContributionsFor(FEATURE))
                .thenReturn(List.of(
                        new PriorContribution("c1", "a", FEATURE, 0.8, 50, null),
                        new PriorContribution("c2", "b", FEATURE, 0.6, 50, null)));
        PriorsService service = new PriorsService(repo, new PriorsGovernance(poolingOnProps()), mode(true));

        assertTrue(service.derive(FEATURE).isEmpty(), "no prior crosses the boundary in single-tenant mode");
        verify(repo, never()).consentedContributionsFor(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void poolingPermittedOnlyWhenModeIsOff() {
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        when(repo.hasConsent(ORG)).thenReturn(true);

        // Mode OFF: the existing per-feature governance gates apply again (consent is consulted).
        PriorsService service = new PriorsService(repo, new PriorsGovernance(poolingOnProps()), mode(false));
        assertTrue(service.crossCustomerPoolingPermitted());

        assertTrue(
                service.contribute(ORG, FEATURE, 0.5, 100, null), "with mode off and consent, contribution is pooled");
        verify(repo).hasConsent(ORG);
    }

    @Test
    void deriveReturnsEmpty_whenPoolingFeatureOff_evenWithModeOff() {
        // Sanity: the two gates are independent — mode off but feature off still derives nothing.
        PriorsProperties featureOff = poolingOnProps();
        featureOff.setEnabled(false);
        PriorContributionRepository repo = mock(PriorContributionRepository.class);
        PriorsService service = new PriorsService(repo, new PriorsGovernance(featureOff), mode(false));

        assertEquals(Optional.empty(), service.derive(FEATURE));
        verify(repo, never()).consentedContributionsFor(org.mockito.ArgumentMatchers.any());
    }
}

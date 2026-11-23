// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.tenant.OrganizationCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

/** The new-org overrides run after the org is written, so a failure there must not undo the sign-in. */
class NewOrgCapabilitiesTest {

    /**
     * A database error while writing the overrides: the listener logs it and returns, so the sign-in that created the
     * org still succeeds and the org keeps the build default. Bug caught: the error escapes the listener and fails a
     * sign-in whose org already exists.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFailedOverrideWriteLeavesTheSignInAndTheCacheAlone() {
        OrgFeatureFlagRepository overrides = mock(OrgFeatureFlagRepository.class);
        doThrow(new DataAccessResourceFailureException("down"))
                .when(overrides)
                .insertIfAbsent(anyString(), anyString(), anyBoolean());
        ObjectProvider<DbFeatureFlags> flags = mock(ObjectProvider.class);

        new NewOrgCapabilities(overrides, flags).onOrganizationCreated(new OrganizationCreatedEvent("org_1"));

        verify(overrides).insertIfAbsent("org_1", Capability.RCA_AUTOMATIC.wire(), true);
        verifyNoInteractions(flags);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The open flag adapter's three load-bearing properties: it manufactures no defaults, it reads an org's
 * whole map once rather than once per key, and a write is visible immediately rather than after the TTL.
 */
class DbFeatureFlagsTest {

    private static final String ORG = "org_1";

    private OrgFeatureFlagRepository repo;
    private DbFeatureFlags flags;
    private final Map<String, Boolean> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(OrgFeatureFlagRepository.class);
        when(repo.findByOrg(anyString())).thenAnswer(inv -> Map.copyOf(rows));
        flags = new DbFeatureFlags(repo);
    }

    @Test
    void aGlobalEvaluationHasNoOpinion() {
        rows.put("graders_enabled", false);
        assertEquals(
                Optional.empty(),
                flags.override("graders_enabled", FlagContext.global()),
                "an override is one org's opinion about itself; there is no global row to find");
    }

    /**
     * The bug: a context carrying a blank org id queries the override table for an org named by whitespace
     * instead of landing on the default. A blank org is a global evaluation and has no opinion.
     */
    @Test
    void aBlankOrgHasNoOpinionAndReadsNothing() {
        rows.put("graders_enabled", false);

        assertEquals(Optional.empty(), flags.override("graders_enabled", FlagContext.forOrg("  ")));
        verify(repo, never()).findByOrg(anyString());
    }

    /**
     * The reason the cache exists. Resolving a capability payload asks about all 21 capabilities and the
     * interceptor asks again per gated request; without this it would be a SELECT per question.
     */
    @Test
    void resolvingManyKeysReadsTheOrgOnce() {
        for (int i = 0; i < 21; i++) {
            flags.override("cap_" + i + "_enabled", FlagContext.forOrg(ORG));
        }
        verify(repo, Mockito.times(1)).findByOrg(ORG);
    }
}

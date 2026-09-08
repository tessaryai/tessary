// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    void aKeyWithNoRowHasNoOpinion() {
        assertEquals(
                Optional.empty(),
                flags.override("graders_enabled", FlagContext.forOrg(ORG)),
                "empty means nobody decided — the caller supplies the edition default, not this class");
    }

    @Test
    void aRowIsReturnedInBothDirections() {
        rows.put("graders_enabled", false);
        rows.put("triage_automatic_enabled", true);

        assertFalse(flags.override("graders_enabled", FlagContext.forOrg(ORG)).orElseThrow());
        assertTrue(flags.override("triage_automatic_enabled", FlagContext.forOrg(ORG))
                .orElseThrow());
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

    @Test
    void invalidatingMakesTheNextReadSeeTheWrite() {
        assertEquals(Optional.empty(), flags.override("graders_enabled", FlagContext.forOrg(ORG)));

        rows.put("graders_enabled", false);
        assertEquals(
                Optional.empty(),
                flags.override("graders_enabled", FlagContext.forOrg(ORG)),
                "still the cached map — this is the ten seconds another node would serve");

        flags.invalidate(ORG);
        assertFalse(
                flags.override("graders_enabled", FlagContext.forOrg(ORG)).orElseThrow(),
                "the write path invalidates, so an operator's toggle bites at once on this node");
        verify(repo, Mockito.times(2)).findByOrg(ORG);
    }

    @Test
    void oneOrgsCacheIsNotAnothersAnswer() {
        rows.put("graders_enabled", false);
        assertFalse(flags.override("graders_enabled", FlagContext.forOrg(ORG)).orElseThrow());

        rows.clear();
        assertEquals(
                Optional.empty(),
                flags.override("graders_enabled", FlagContext.forOrg("org_2")),
                "a second org loads its own map rather than inheriting the first's");
    }
}

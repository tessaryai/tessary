// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * This build's resolution contract, against real {@code org_feature_flag} rows:
 *
 * <ol>
 *   <li>with no rows at all, every capability is on except the set in {@link #OFF_BY_DEFAULT},
 *       which is deliberately not {@code Capability.defaultEnabled()};
 *   <li>an org's row overrides that default in both directions;
 *   <li>clearing the row returns the capability to the default rather than leaving it off;
 *   <li>one org's row does not touch another's.
 * </ol>
 */
@SpringBootTest
class CapabilityFlagLayerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    /** What an open build serves before anybody touches it. Mirrors CapabilityService's two private sets. */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(
            Capability.BEHAVIOR_DRIFT,
            Capability.SOP_CONFORMANCE,
            Capability.FRUSTRATION,
            Capability.GROUNDEDNESS,
            Capability.TRIAGE_AUTOMATIC);

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityService capabilities;

    @Autowired
    OrgFeatureFlagRepository overrides;

    @Autowired
    DbFeatureFlags flags;

    @Test
    void withNoOverrides_everythingIsOnExceptThePaidClassifiersAndAutomaticTriage() {
        var fix = TenantFixture.bootstrap(tenants, "cap-default");
        String orgId = fix.org().id();

        var resolved = capabilities.resolve(orgId);
        for (Capability capability : Capability.values()) {
            boolean expected = !OFF_BY_DEFAULT.contains(capability);
            assertEquals(
                    expected,
                    resolved.isEnabled(capability),
                    capability.wire() + " should default " + (expected ? "on" : "off") + " in an open build");
        }
        // The five that are off are off for two different reasons, and the payload has to say which.
        assertEquals(
                Set.of(
                        Capability.BEHAVIOR_DRIFT,
                        Capability.SOP_CONFORMANCE,
                        Capability.FRUSTRATION,
                        Capability.GROUNDEDNESS),
                Set.copyOf(capabilities.unavailable()),
                "only the four paid classifiers are UNAVAILABLE; triage_automatic is merely off");
    }

    @Test
    void anOrgOverrideWinsInBothDirections() {
        var fix = TenantFixture.bootstrap(tenants, "cap-override");
        String orgId = fix.org().id();

        assertTrue(capabilities.isEnabled(orgId, Capability.ALERTS), "on by default");

        set(orgId, Capability.ALERTS, false);
        assertFalse(capabilities.isEnabled(orgId, Capability.ALERTS), "the row turns it off");
        assertThrows(TessaryException.class, () -> capabilities.require(orgId, Capability.ALERTS));

        assertFalse(capabilities.isEnabled(orgId, Capability.TRIAGE_AUTOMATIC), "off by default");
        set(orgId, Capability.TRIAGE_AUTOMATIC, true);
        assertTrue(capabilities.isEnabled(orgId, Capability.TRIAGE_AUTOMATIC), "the row turns it on");
    }

    @Test
    void clearingAnOverrideReturnsTheCapabilityToTheDefault() {
        var fix = TenantFixture.bootstrap(tenants, "cap-clear");
        String orgId = fix.org().id();

        set(orgId, Capability.API_ACCESS, false);
        assertFalse(capabilities.isEnabled(orgId, Capability.API_ACCESS));

        overrides.delete(orgId, Capability.API_ACCESS.wire());
        flags.invalidate(orgId);
        // Not "off, because false was the last thing written": "on, because nobody has an opinion".
        assertTrue(capabilities.isEnabled(orgId, Capability.API_ACCESS), "back to the open-edition default");
    }

    @Test
    void oneOrgsOverrideLeavesEveryOtherOrgAlone() {
        var us = TenantFixture.bootstrap(tenants, "cap-us");
        var them = TenantFixture.bootstrap(tenants, "cap-them");

        set(us.org().id(), Capability.SLACK, false);

        assertFalse(capabilities.isEnabled(us.org().id(), Capability.SLACK), "the org that decided");
        assertTrue(
                capabilities.isEnabled(them.org().id(), Capability.SLACK),
                "every other org is untouched — an override is one org's opinion about itself");
    }

    /** Write a row and drop the ten-second cache, which is what the override endpoint does. */
    private void set(String orgId, Capability capability, boolean enabled) {
        overrides.upsert(orgId, capability.wire(), enabled);
        flags.invalidate(orgId);
    }
}

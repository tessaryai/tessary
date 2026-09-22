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
import ai.tessary.testsupport.EncoderFixture;
import ai.tessary.testsupport.TenantFixture;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This build's resolution contract, against real {@code org_feature_flag} rows:
 *
 * <ol>
 *   <li>with no rows at all, every capability is on except the set in {@link #OFF_BY_DEFAULT},
 *       which is deliberately not {@code Capability.defaultEnabled()};
 *   <li>an org's row overrides that default in both directions;
 *   <li>clearing the row returns the capability to the default rather than leaving it off;
 *   <li>one org's row does not touch another's;
 *   <li>an encoder-backed classifier is on only while the instance's encoder answers, and an
 *       org's row can then only take it away.
 * </ol>
 */
@SpringBootTest
class CapabilityFlagLayerTest {

    /**
     * What an open build serves before anybody touches it, on an instance whose encoder is not
     * answering (this test context configures none). Mirrors CapabilityService's three private sets.
     */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(
            Capability.BEHAVIOR_DRIFT,
            Capability.SOP_CONFORMANCE,
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

    @Autowired
    EncoderFixture encoder;

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
        // The four that are off are off for different reasons, and the payload has to say which:
        // two the build does not carry, one the instance cannot reach an encoder for, and one
        // that is merely an opt-in.
        assertEquals(
                Set.of(Capability.BEHAVIOR_DRIFT, Capability.SOP_CONFORMANCE, Capability.GROUNDEDNESS),
                Set.copyOf(capabilities.unavailable()),
                "the paid classifiers and the encoder-backed one are UNAVAILABLE; triage_automatic is merely off");
    }

    @Test
    void anEncoderBackedClassifierFollowsTheEncoderAndAnOrgCanOnlyNarrowIt() {
        var fix = TenantFixture.bootstrap(tenants, "cap-encoder");
        String orgId = fix.org().id();
        try {
            assertFalse(capabilities.isEnabled(orgId, Capability.GROUNDEDNESS), "no encoder, no classifier");
            set(orgId, Capability.GROUNDEDNESS, true);
            assertFalse(
                    capabilities.isEnabled(orgId, Capability.GROUNDEDNESS),
                    "an org's ON does not outrank the instance: there is nowhere to send the requests");

            encoder.up();
            overrides.delete(orgId, Capability.GROUNDEDNESS.wire());
            flags.invalidate(orgId);
            assertTrue(capabilities.isEnabled(orgId, Capability.GROUNDEDNESS), "the encoder answers: on by default");
            assertFalse(capabilities.unavailable().contains(Capability.GROUNDEDNESS), "and no longer unavailable");

            set(orgId, Capability.GROUNDEDNESS, false);
            assertFalse(capabilities.isEnabled(orgId, Capability.GROUNDEDNESS), "the org turned it off");

            encoder.down();
            overrides.delete(orgId, Capability.GROUNDEDNESS.wire());
            flags.invalidate(orgId);
            assertFalse(capabilities.isEnabled(orgId, Capability.GROUNDEDNESS), "the encoder went away: off again");
        } finally {
            encoder.down();
        }
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

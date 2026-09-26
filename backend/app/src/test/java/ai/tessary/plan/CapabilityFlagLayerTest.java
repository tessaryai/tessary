// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityController.OverrideView;
import ai.tessary.plan.CapabilityController.SetOverrideRequest;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * This build's resolution contract, against real {@code org_feature_flag} rows:
 *
 * <ol>
 *   <li>with no rows at all, every capability is on except the set in {@link #OFF_BY_DEFAULT};
 *   <li>an org's row overrides that default in both directions;
 *   <li>clearing the row returns the capability to the default rather than leaving it off;
 *   <li>one org's row does not touch another's.
 * </ol>
 */
@SpringBootTest
class CapabilityFlagLayerTest {

    /** What an open build serves before anybody touches it. Mirrors CapabilityService's private set. */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(Capability.TRIAGE_AUTOMATIC);

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityService capabilities;

    @Autowired
    OrgFeatureFlagRepository overrides;

    @Autowired
    DbFeatureFlags flags;

    @Autowired
    CapabilityController controller;

    @Autowired
    OrgMembershipRepository memberships;

    @Test
    void withNoOverrides_everythingIsOnExceptAutomaticTriage() {
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

    @Test
    void theCapabilityPayloadStatesEveryCapabilityInCatalogOrder() {
        var fix = TenantFixture.bootstrap(tenants, "cap-payload");
        set(fix.org().id(), Capability.SLACK, false);

        Map<String, Boolean> expected = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            expected.put(capability.wire(), capability != Capability.SLACK && !OFF_BY_DEFAULT.contains(capability));
        }
        var payload = controller
                .capabilities(session(fix.user()), fix.org().slug())
                .data()
                .capabilities();

        assertEquals(expected, payload, "every key present with an explicit boolean");
        assertEquals(
                List.copyOf(expected.keySet()),
                List.copyOf(payload.keySet()),
                "in declaration order, so two orgs' payloads diff by eye");
    }

    /**
     * The endpoint drops the ten-second flag cache on every write, so an operator who turns a
     * capability off sees it off on the next read rather than ten seconds later.
     */
    @Test
    void anOverrideSetThroughTheEndpointBitesOnTheNextReadAndClearingItRestoresTheDefault() {
        var fix = TenantFixture.bootstrap(tenants, "cap-endpoint");
        TenantContext owner = session(fix.user());
        String slug = fix.org().slug();
        assertTrue(controller.capabilities(owner, slug).data().capabilities().get("alerts_enabled"), "cache warm");

        OverrideView set = controller
                .setOverride(owner, slug, "alerts_enabled", new SetOverrideRequest(false))
                .data();

        assertEquals(new OverrideView("alerts_enabled", false, true, true), set);
        assertFalse(controller.capabilities(owner, slug).data().capabilities().get("alerts_enabled"));
        assertEquals(
                new OverrideView("alerts_enabled", false, true, true),
                controller.overrides(owner, slug).data().stream()
                        .filter(v -> v.capability().equals("alerts_enabled"))
                        .findFirst()
                        .orElseThrow());

        OverrideView cleared =
                controller.clearOverride(owner, slug, "alerts_enabled").data();

        assertEquals(new OverrideView("alerts_enabled", true, true, false), cleared);
        assertTrue(controller.capabilities(owner, slug).data().capabilities().get("alerts_enabled"));
    }

    @Test
    void aWriteToARetiredOrUnknownCapabilityIsRefused() {
        var fix = TenantFixture.bootstrap(tenants, "cap-unknown");
        TenantContext owner = session(fix.user());

        TessaryException set = assertThrows(
                TessaryException.class,
                () -> controller.setOverride(owner, fix.org().slug(), "graders_enabled", new SetOverrideRequest(true)));
        TessaryException clear = assertThrows(
                TessaryException.class,
                () -> controller.clearOverride(owner, fix.org().slug(), "graders_enabled"));

        assertEquals(CapabilityError.UNKNOWN, set.error());
        assertEquals(CapabilityError.UNKNOWN, clear.error());
        assertEquals(Map.of(), overrides.findByOrg(fix.org().id()), "nothing was written for a key nobody reads");
    }

    @Test
    void aMemberMaySeeTheOverridesButNotChangeThem() {
        var fix = TenantFixture.bootstrap(tenants, "cap-member");
        Principal member = tenants.upsertUserFromWorkos(
                "user_cap_member_" + System.nanoTime(), "cap-member+" + System.nanoTime() + "@example.com", "m", null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), OrgMembership.MEMBER, Instant.now().toString()));
        TenantContext ctx = session(member);

        assertEquals(
                Capability.values().length,
                controller.overrides(ctx, fix.org().slug()).data().size());
        ResponseStatusException set = assertThrows(
                ResponseStatusException.class,
                () -> controller.setOverride(ctx, fix.org().slug(), "alerts_enabled", new SetOverrideRequest(false)));
        ResponseStatusException clear = assertThrows(
                ResponseStatusException.class,
                () -> controller.clearOverride(ctx, fix.org().slug(), "alerts_enabled"));

        assertEquals(HttpStatus.FORBIDDEN, set.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, clear.getStatusCode());
        assertTrue(capabilities.isEnabled(fix.org().id(), Capability.ALERTS), "the refused write changed nothing");
    }

    private static TenantContext session(Principal user) {
        return new TenantContext(user.id(), user.email(), null, null, null, null);
    }

    /** Write a row and drop the ten-second cache, which is what the override endpoint does. */
    private void set(String orgId, Capability capability, boolean enabled) {
        overrides.upsert(orgId, capability.wire(), enabled);
        flags.invalidate(orgId);
    }
}

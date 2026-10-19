// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.license.License;
import ai.tessary.license.LicenseSource;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityController.OverrideView;
import ai.tessary.plan.CapabilityController.SetOverrideRequest;
import ai.tessary.tenant.Organization;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The Features screen's contract when a license is in force: a licensed-off capability is shown locked and refused. */
class CapabilityControllerTest {

    private static final Organization ORG = new Organization("org_1", null, "acme", "Acme", "t0", null, null);

    private final Map<String, Boolean> stored = new HashMap<>();
    private final FeatureFlags flags = (key, ctx) -> Optional.ofNullable(stored.get(key));
    private final LicenseSource rcaOff = () -> Optional.of(new License(
            "lic_1",
            null,
            null,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2027, 10, 1),
            14,
            Map.of(Capability.RCA.wire(), false),
            Map.of()));
    private final OrgFeatureFlagRepository overrides = mock(OrgFeatureFlagRepository.class);
    private final TenantContext ctx = mock(TenantContext.class);

    @SuppressWarnings("unchecked")
    private CapabilityController controller() {
        TenantPathResolver resolver = mock(TenantPathResolver.class);
        when(resolver.requireOrg(ctx, "acme")).thenReturn(new TenantPathResolver.OrgResolved(ORG, "owner"));
        when(overrides.findByOrg("org_1")).thenReturn(stored);
        return new CapabilityController(
                new CapabilityService(flags, CapabilityDefaults.open(), rcaOff), overrides, resolver, (ObjectProvider<
                                DbFeatureFlags>)
                        mock(ObjectProvider.class));
    }

    @Test
    void aLicensedOffCapabilityIsRefusedAnOverrideAndShownLocked() {
        CapabilityController controller = controller();

        TessaryException refused = assertThrows(
                TessaryException.class,
                () -> controller.setOverride(ctx, "acme", Capability.RCA.wire(), new SetOverrideRequest(true)));

        assertEquals(CapabilityError.LICENSED, refused.error());
        verify(overrides, never()).upsert(any(), any(), eq(true));
        List<OverrideView> views = controller.overrides(ctx, "acme").data();
        OverrideView rca = views.stream()
                .filter(v -> v.capability().equals(Capability.RCA.wire()))
                .findFirst()
                .orElseThrow();
        assertEquals(new OverrideView(Capability.RCA.wire(), false, true, false, false), rca);
        assertFalse(controller.capabilities(ctx, "acme").data().capabilities().get(Capability.RCA.wire()));
    }

    @Test
    void anUnlicensedCapabilityStillTakesAnOverride() {
        CapabilityController controller = controller();

        OverrideView set = controller
                .setOverride(ctx, "acme", Capability.SLACK.wire(), new SetOverrideRequest(false))
                .data();

        verify(overrides).upsert("org_1", Capability.SLACK.wire(), false);
        assertTrue(set.licensed());
        assertTrue(controller
                .clearOverride(ctx, "acme", Capability.SLACK.wire())
                .data()
                .licensed());
    }
}

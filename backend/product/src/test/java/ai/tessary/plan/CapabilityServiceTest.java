// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.featureflags.FeatureFlags;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A default comes from whichever {@link CapabilityDefaults} the build supplies; an org's override still wins. */
class CapabilityServiceTest {

    private static final String ORG_ID = "org_1";

    private final Map<String, Boolean> overrides = new HashMap<>();
    private final FeatureFlags flags = (key, ctx) -> Optional.ofNullable(overrides.get(key));

    /** Another build's defaults: automatic triage on, everything else as this build ships it. */
    private final CapabilityDefaults triageOn =
            c -> c == Capability.TRIAGE_AUTOMATIC || CapabilityDefaults.open().defaultFor(c);

    @Test
    void theOpenDefaultsLeaveAutomaticTriageOff() {
        var service = new CapabilityService(flags, CapabilityDefaults.open());
        assertFalse(service.isEnabled(ORG_ID, Capability.TRIAGE_AUTOMATIC));
        assertFalse(service.defaultFor(Capability.TRIAGE_AUTOMATIC));
    }

    @Test
    void suppliedDefaultsDecideAnOrgWithNoOverride() {
        var service = new CapabilityService(flags, triageOn);
        assertTrue(service.isEnabled(ORG_ID, Capability.TRIAGE_AUTOMATIC));
        assertTrue(service.defaultFor(Capability.TRIAGE_AUTOMATIC));
        assertFalse(service.isEnabled(ORG_ID, Capability.ALERTS), "only the capability the defaults flip moves");
    }

    @Test
    void anOrgOverrideWinsOverSuppliedDefaults() {
        overrides.put(Capability.TRIAGE_AUTOMATIC.wire(), false);
        var service = new CapabilityService(flags, triageOn);
        assertFalse(service.isEnabled(ORG_ID, Capability.TRIAGE_AUTOMATIC));
        assertFalse(service.resolve(ORG_ID).isEnabled(Capability.TRIAGE_AUTOMATIC));
    }
}

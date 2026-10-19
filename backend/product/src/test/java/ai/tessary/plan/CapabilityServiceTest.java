// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.license.License;
import ai.tessary.license.LicenseSource;
import java.time.LocalDate;
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
        var service = new CapabilityService(flags, CapabilityDefaults.open(), LicenseSource.none());
        assertFalse(service.isEnabled(ORG_ID, Capability.TRIAGE_AUTOMATIC));
        assertFalse(service.defaultFor(Capability.TRIAGE_AUTOMATIC));
    }

    @Test
    void anOrgOverrideWinsOverSuppliedDefaults() {
        overrides.put(Capability.TRIAGE_AUTOMATIC.wire(), false);
        var service = new CapabilityService(flags, triageOn, LicenseSource.none());
        assertFalse(service.isEnabled(ORG_ID, Capability.TRIAGE_AUTOMATIC));
        assertFalse(service.resolve(ORG_ID).isEnabled(Capability.TRIAGE_AUTOMATIC));
    }

    /** A license that lists RCA off, says nothing about the rest. */
    private static final LicenseSource RCA_OFF = () -> Optional.of(new License(
            "lic_1",
            null,
            null,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2027, 10, 1),
            14,
            Map.of(Capability.RCA.wire(), false, Capability.SLACK.wire(), true),
            Map.of()));

    /** The bug: an org admin turning on a capability the deployment did not license, by writing an override. */
    @Test
    void theLicenseCeilingWinsOverAnOverrideAndTheDefault() {
        overrides.put(Capability.RCA.wire(), true);
        var service = new CapabilityService(flags, CapabilityDefaults.open(), RCA_OFF);

        assertFalse(service.isEnabled(ORG_ID, Capability.RCA));
        assertFalse(service.resolve(ORG_ID).isEnabled(Capability.RCA));
        assertFalse(service.licensed(Capability.RCA));
        assertTrue(service.defaultFor(Capability.RCA), "the default is untouched: the license is a ceiling");
    }

    /** A license grants nothing: a capability it lists true still resolves from the override and the default. */
    @Test
    void aLicenseNeverTurnsACapabilityOn() {
        overrides.put(Capability.SLACK.wire(), false);
        var service = new CapabilityService(flags, CapabilityDefaults.open(), RCA_OFF);

        assertFalse(service.isEnabled(ORG_ID, Capability.SLACK), "the override still wins");
        assertTrue(service.licensed(Capability.SLACK));
        assertTrue(service.licensed(Capability.ALERTS), "a key the license does not name is unconstrained");
        assertFalse(service.isEnabled(ORG_ID, Capability.ALERTS), "and resolves from the default");
    }
}

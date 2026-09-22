// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import ai.tessary.edition.Edition;
import ai.tessary.featureflags.FeatureFlags;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two-level switch on an encoder-backed classifier: the instance's encoder health is a ceiling
 * on the org's own override. Everything else in {@link CapabilityService} is exercised end to end by
 * the app module's {@code CapabilityFlagLayerTest}; this pins the one rule that has no database in it.
 */
class CapabilityServiceEncoderGateTest {

    private static final String ORG = "org-1";

    private final FeatureFlags flags = mock(FeatureFlags.class);
    private final Edition open = mock(Edition.class);

    /** A never-probed encoder reads as unavailable, exactly like an unreachable one. */
    private final EncoderAvailability down = new EncoderAvailability(new ObserverProperties());

    @Test
    void encoderDownWithholdsGroundednessWhateverTheOrgSays() {
        when(flags.override(eq(Capability.GROUNDEDNESS.wire()), any())).thenReturn(Optional.of(true));
        CapabilityService service = new CapabilityService(flags, open, down);

        assertFalse(service.isEnabled(ORG, Capability.GROUNDEDNESS), "an org's ON cannot outrank a missing encoder");
        assertTrue(service.unavailable().contains(Capability.GROUNDEDNESS), "and the payload says unavailable");
        assertEquals(
                Set.of(Capability.BEHAVIOR_DRIFT, Capability.SOP_CONFORMANCE, Capability.GROUNDEDNESS),
                service.unavailable());
    }

    @Test
    void encoderUpMakesGroundednessDefaultOnAndTheOrgMayTurnItOff() {
        EncoderAvailability up = mock(EncoderAvailability.class);
        when(up.available()).thenReturn(true);
        when(flags.override(any(), any())).thenReturn(Optional.empty());
        CapabilityService service = new CapabilityService(flags, open, up);

        assertTrue(service.isEnabled(ORG, Capability.GROUNDEDNESS), "on by default once the instance can run it");
        assertFalse(service.unavailable().contains(Capability.GROUNDEDNESS));
        assertFalse(service.unavailable().contains(Capability.FRUSTRATION), "frustration is not encoder-backed");

        when(flags.override(eq(Capability.GROUNDEDNESS.wire()), any())).thenReturn(Optional.of(false));
        assertFalse(service.isEnabled(ORG, Capability.GROUNDEDNESS), "the org's OFF is honoured");
    }

    @Test
    void theGateTouchesOnlyTheEncoderBackedCapabilities() {
        when(flags.override(any(), any())).thenReturn(Optional.empty());
        CapabilityService service = new CapabilityService(flags, open, down);

        assertTrue(service.isEnabled(ORG, Capability.ALERTS));
        assertTrue(service.isEnabled(ORG, Capability.SECRET_LEAK));
        assertFalse(service.isEnabled(ORG, Capability.TRIAGE_AUTOMATIC), "still merely off, not unavailable");
        assertFalse(service.unavailable().contains(Capability.TRIAGE_AUTOMATIC));
    }
}

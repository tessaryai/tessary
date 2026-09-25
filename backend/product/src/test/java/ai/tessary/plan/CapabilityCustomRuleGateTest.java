// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Custom redaction rules are the {@code custom_redaction} capability and no other. */
class CapabilityCustomRuleGateTest {

    private final Map<String, Boolean> overrides = new HashMap<>();
    private final FeatureFlags flags = (key, ctx) -> Optional.ofNullable(overrides.get(key));
    private final CapabilityCustomRuleGate gate = new CapabilityCustomRuleGate(new CapabilityService(flags));

    @Test
    void customRulesAreRefusedOnceTheOrgSwitchesTheCapabilityOff() {
        assertDoesNotThrow(() -> gate.requireCustomRules("org_1"), "on by default");

        overrides.put(Capability.CUSTOM_REDACTION.wire(), false);
        TessaryException e = assertThrows(TessaryException.class, () -> gate.requireCustomRules("org_1"));

        assertEquals(CapabilityError.DISABLED, e.error());
        assertEquals(CapabilityError.DISABLED.render(Capability.CUSTOM_REDACTION.wire()), e.getMessage());
    }
}

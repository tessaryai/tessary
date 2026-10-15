// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.redaction.CustomRuleGate;
import org.springframework.stereotype.Component;

/**
 * Resolves {@link Capability#CUSTOM_REDACTION} through {@link CapabilityService}. Lives in {@code product} because the
 * dependency must point that way.
 */
@Component
public class CapabilityCustomRuleGate implements CustomRuleGate {

    private final CapabilityService capabilities;

    public CapabilityCustomRuleGate(CapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public void requireCustomRules(String orgId) {
        capabilities.require(orgId, Capability.CUSTOM_REDACTION);
    }
}

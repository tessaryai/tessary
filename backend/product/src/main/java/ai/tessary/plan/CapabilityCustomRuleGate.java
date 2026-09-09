// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.redaction.CustomRuleGate;
import org.springframework.stereotype.Component;

/**
 * The one implementation of {@link CustomRuleGate}: resolve {@link Capability#CUSTOM_REDACTION} for the org
 * through the same {@link CapabilityService} every other gated surface uses.
 *
 * <p>It lives in {@code product} beside the capability layer rather than in {@code substrate} beside the
 * controller, because that is the direction the dependency has to run — see the interface's own note.
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

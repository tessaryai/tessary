// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a capability is on for an org with no override. {@link CapabilitySeamConfig} registers {@link #open()} only
 * when no other build supplies a bean. An org's override still wins.
 */
public interface CapabilityDefaults {

    boolean defaultFor(Capability capability);

    /**
     * On, except automatic Layer-2 triage, which drives LLM escalation with no ceiling and so is a knowing opt-in, and
     * alerts, which are not shipping in this build.
     */
    static CapabilityDefaults open() {
        Set<Capability> offByDefault = EnumSet.of(Capability.TRIAGE_AUTOMATIC, Capability.ALERTS);
        return capability -> !offByDefault.contains(capability);
    }
}

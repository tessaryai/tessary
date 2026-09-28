// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a capability is on for an org that states no override of its own. This build's answer is
 * {@link #open()}, and {@link CapabilitySeamConfig} registers it only when no other build supplies
 * one, so another build changes a default by shipping a bean rather than writing a row or reading an
 * environment variable. An org's override still wins over whatever this answers.
 */
public interface CapabilityDefaults {

    boolean defaultFor(Capability capability);

    /**
     * On, except automatic Layer-2 triage and alerts. Automatic triage drives LLM escalation with no
     * ceiling, so running it unattended is an opt-in an operator takes knowingly. Alerts are not
     * shipping in this build, so their settings page stays hidden and nothing is delivered.
     */
    static CapabilityDefaults open() {
        Set<Capability> offByDefault = EnumSet.of(Capability.TRIAGE_AUTOMATIC, Capability.ALERTS);
        return capability -> !offByDefault.contains(capability);
    }
}

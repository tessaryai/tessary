// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

/**
 * Gates authoring a custom redaction rule ({@code custom_redaction_enabled}). An interface because
 * {@code CapabilityService} lives above {@code substrate}. Reading rules, disabling built-ins and previewing stay open
 * to every org, since that page is what a security review reads.
 */
public interface CustomRuleGate {

    /** Throws {@code 403 CAPABILITY_DISABLED}. */
    void requireCustomRules(String orgId);
}

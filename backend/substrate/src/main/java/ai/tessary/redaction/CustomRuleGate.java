// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

/**
 * The gate on <b>authoring</b> a custom redaction rule — {@code custom_redaction_enabled}, off at launch.
 *
 * <p>An interface rather than a direct call because of where the two halves sit: the capability layer
 * ({@code plan/CapabilityService}) lives in {@code product}, and {@code redaction} lives one module BELOW it
 * in {@code substrate}. That is the same layering wall segment H hit with {@code ProviderCredentialController},
 * and it is why {@code custom_redaction_enabled} shipped declared-but-unenforced: there was no downward call
 * to make. The repo's own convention for an edge that must point up is an interface owned by the lower layer
 * and implemented above ({@code ingest/CallSiteRegistry} is the precedent), which is this.
 *
 * <p><b>What it does NOT gate, deliberately.</b> Reading the rule list, disabling a built-in, and previewing
 * the whole active rule set stay open to every org. The page is what a partner's security review reads to
 * find out what we strip before we store anything (launch requirement K3); gating the page would hide the
 * defaults along with the authoring tool, which is exactly backwards. The capability governs writing a rule
 * of your own, and nothing else.
 */
public interface CustomRuleGate {

    /** Throw {@code 403 CAPABILITY_DISABLED} unless {@code orgId} may author custom redaction rules. */
    void requireCustomRules(String orgId);
}

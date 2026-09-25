// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.util.List;
import java.util.Optional;

/**
 * Something the platform can do for an organization, and the flag that decides whether it does.
 *
 * <p>There is exactly one axis: a capability has this build's default ({@code CapabilityService}),
 * and an org's own override wins over it.
 *
 * <p>{@link #wire()} is the flag key, the capability's identifier in the API payload, and the key the
 * SPA gates on. It is deliberately one string rather than a name plus a derived key: two identifiers
 * for the same thing is how an override ends up pointing at a flag nobody is reading.
 *
 * <p>This build's {@code CapabilityService} serves every capability on except {@code
 * TRIAGE_AUTOMATIC}.
 *
 * <p>Adding a capability is adding a constant here. Nothing else in the codebase learns a
 * capability's name; surfaces ask this enum.
 */
public enum Capability {

    // ---- the calibrate half ----

    // Four wire keys are retired and must not be reintroduced: graders_enabled, observer_enabled,
    // human_review_enabled, agentic_synthesis_enabled. Every surface they gated was deleted, so a
    // flag for them would gate nothing. Their persisted org_feature_flag rows are deleted by
    // changeset 0016; CapabilityController 422s a write to an unknown wire key, so an org that had
    // pinned one off would otherwise hit that on the override endpoint with no way to clear it.
    // behavior_drift_enabled and sop_conformance_enabled are retired too: this build never shipped
    // either classifier.

    /** Pre-merge bug prediction on PRs/branches (the CI integration). */
    CI_INTEGRATION("ci_integration_enabled"),
    /**
     * The agentic root-cause surface: the RCA reports and the agent that writes them.
     *
     * <p>An RCA run is an uncapped agent session billed to the org's own provider credential, like
     * every other agentic lane, so this flag is a spend decision as much as a product one.
     *
     * <p>What the flag gates is the REST trigger and the UI that calls it, the places a run can
     * actually be started. A report reaches an agent inlined on the case that owns it, so an org
     * without this flag has no report rows to inline and needs no gate on the read.
     */
    RCA("rca_enabled"),

    // ---- platform services the launch product needs ----

    /** Programmatic API access via project tokens. */
    API_ACCESS("api_access_enabled"),
    /** Alerts, since a case has to reach a human. The transport is a channel, and which channels an
     *  org may use is a separate question (see {@link #SLACK}). */
    ALERTS("alerts_enabled"),
    /**
     * The whole Slack surface: the {@code slack} alert channel, the native app's outbound channel posts,
     * and the inbound {@code @mention} reply.
     *
     * <p>One flag rather than three, since the three are one thing to a person deciding whether to
     * integrate with Slack. It is a different axis from {@link #ALERTS}: alerting stays on and a case
     * still reaches a human over a generic webhook, so turning Slack off costs a transport, not the
     * capability.
     *
     * <p>Distinct from {@code tessary.slack.*}, the deploy-level switch for whether the platform has a
     * Slack app's credentials at all. That one is about the deployment; this one is about an
     * organization. Both must be on for a Slack message to be sent, and they fail closed
     * independently.
     */
    SLACK("slack_enabled"),
    /** Custom PII redaction rules on top of the defaults. */
    CUSTOM_REDACTION("custom_redaction_enabled"),

    /**
     * Whether the org may store its own provider credentials (Settings → Providers) and have work
     * billed to them instead of to us.
     *
     * <p>Every agentic lane resolves the org's own row through
     * {@code AgenticCredentialResolver} and fails closed with
     * {@code ModelConfigError.MISSING_CREDENTIALS} when it finds none, so there is nothing else for a
     * lane to run on.
     *
     * <p>With this off, {@code ProviderCredentialController} refuses every write and no other code
     * path in the tree creates a credential row, so the org has no credential, Layer-2 triage cannot
     * run on any finding, no finding can become a case, and findings accumulate with a null
     * {@code triage_verdict} forever. Turning it off does not protect spend, it makes the product
     * inert, so targeting one org off is a deliberate suspension rather than the shipping default.
     */
    BYO_PROVIDER_KEYS("byo_provider_keys_enabled"),

    // ---- the classifier catalog ----

    /** The {@code duration_drift} built-in classifier. */
    DURATION_DRIFT("duration_drift_enabled"),
    /** The {@code cost_drift} built-in classifier. */
    COST_DRIFT("cost_drift_enabled"),
    /** The {@code tool_error} built-in classifier. */
    TOOL_ERROR("tool_error_enabled"),
    /** The {@code frustration} built-in classifier. */
    FRUSTRATION("frustration_enabled"),
    /** The {@code groundedness} built-in classifier. */
    GROUNDEDNESS("groundedness_enabled"),
    /** The {@code secret_leak} built-in classifier. */
    SECRET_LEAK("secret_leak_enabled"),
    /** The {@code malformed_output} built-in classifier. */
    MALFORMED_OUTPUT("malformed_output_enabled"),

    // ---- triage ----

    /**
     * Whether Layer-2 triage runs on its own rather than on a press. Off by default ({@code
     * CapabilityService.OFF_BY_DEFAULT}) because the LLM escalation it drives is uncapped: automatic
     * is an opt-in an org takes knowingly, per org, through an override. That spend lands on the
     * org's own provider credential rather than on us, which makes the opt-in more clearly theirs to
     * take and no less necessary.
     */
    TRIAGE_AUTOMATIC("triage_automatic_enabled");

    private final String wire;

    Capability(String wire) {
        this.wire = wire;
    }

    /** The flag key, which is also this capability's identifier everywhere else. Immutable. */
    public String wire() {
        return wire;
    }

    /** Resolve a wire value to the enum, or empty if unknown (callers decide whether unknown is fatal). */
    public static Optional<Capability> fromWire(String value) {
        for (Capability c : values()) {
            if (c.wire.equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Every capability, in declaration order: the read surface for the per-session capability payload. */
    public static List<Capability> all() {
        return List.of(values());
    }
}

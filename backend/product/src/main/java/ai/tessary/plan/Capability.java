// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.util.List;
import java.util.Optional;

/**
 * Something the platform can do for an organization, and the flag that decides whether it does.
 *
 * <p>There is exactly one axis: a capability has a platform default declared here, a plan tier may
 * override that default, and an org's own override wins over both. Whoever is most specific wins.
 *
 * <p>{@link #wire()} is the flag key, the capability's identifier in the API payload, and the key the
 * SPA gates on. It is deliberately one string rather than a name plus a derived key: two identifiers
 * for the same thing is how an override ends up pointing at a flag nobody is reading.
 *
 * <p>This build's {@code CapabilityService} serves every capability on except {@code
 * TRIAGE_AUTOMATIC} and the classifiers this tree does not ship, regardless of the default declared
 * here.
 *
 * <p>Adding a capability is adding a constant here with a stated default. Nothing else in the
 * codebase learns a capability's name; surfaces ask this enum.
 */
public enum Capability {

    // ---- the calibrate half: on for us, off for launch partners ----

    // Four wire keys are retired and must not be reintroduced: graders_enabled, observer_enabled,
    // human_review_enabled, agentic_synthesis_enabled. Every surface they gated was deleted, so a
    // flag for them would gate nothing. Their persisted org_feature_flag rows are deleted by
    // changeset 0016; CapabilityController 422s a write to an unknown wire key, so an org that had
    // pinned one off would otherwise hit that on the override endpoint with no way to clear it.

    /** Pre-merge bug prediction on PRs/branches (the CI integration). */
    CI_INTEGRATION("ci_integration_enabled", false),
    /**
     * The agentic root-cause surface: the RCA reports and the agent that writes them.
     *
     * <p>Off by default. An RCA run is an uncapped agent session billed to the org's own provider
     * credential, like every other agentic lane, so this flag is a spend decision as much as a
     * product one, and it is an opt-in an org takes knowingly through an override.
     *
     * <p>What the flag gates is the REST trigger and the UI that calls it, the places a run can
     * actually be started. A report reaches an agent inlined on the case that owns it, so an org
     * without this flag has no report rows to inline and needs no gate on the read.
     */
    RCA("rca_enabled", false),

    // ---- platform services the launch product needs ----

    /** Programmatic API access via project tokens. */
    API_ACCESS("api_access_enabled", true),
    /** Alerts, on by default, since a case has to reach a human. The transport is a channel, and which
     *  channels an org may use is a separate question (see {@link #SLACK}). */
    ALERTS("alerts_enabled", true),
    /**
     * The whole Slack surface: the {@code slack} alert channel, the native app's outbound channel posts,
     * and the inbound {@code @mention} reply. Off by default.
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
    SLACK("slack_enabled", false),
    /** Custom PII redaction rules on top of the defaults. */
    CUSTOM_REDACTION("custom_redaction_enabled", false),

    /**
     * Whether the org may store its own provider credentials (Settings → Providers) and have work
     * billed to them instead of to us.
     *
     * <p>On by default: every agentic lane resolves the org's own row through
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
    BYO_PROVIDER_KEYS("byo_provider_keys_enabled", true),

    // ---- the classifier catalog: three on at launch, six defined and off ----

    /** The {@code duration_drift} built-in classifier. */
    DURATION_DRIFT("duration_drift_enabled", true),
    /** The {@code cost_drift} built-in classifier. */
    COST_DRIFT("cost_drift_enabled", true),
    /**
     * The {@code tool_error} built-in classifier. Declared and defaulted on ahead of the detector
     * itself: a capability with no module behind it seeds nothing until the detector lands, and a
     * flag that appears the same day as its detector cannot be targeted in advance.
     */
    TOOL_ERROR("tool_error_enabled", true),
    /** The {@code frustration} built-in classifier. */
    FRUSTRATION("frustration_enabled", false),
    /** The {@code groundedness} built-in classifier. */
    GROUNDEDNESS("groundedness_enabled", false),
    /** The {@code secret_leak} built-in classifier. */
    SECRET_LEAK("secret_leak_enabled", false),
    /** The {@code malformed_output} built-in classifier. */
    MALFORMED_OUTPUT("malformed_output_enabled", false),
    /** The {@code behavior_drift} built-in classifier. */
    BEHAVIOR_DRIFT("behavior_drift_enabled", false),
    /**
     * The {@code sop_conformance} built-in classifier. Off, and targeted on for no org yet.
     *
     * <p>What keeps it off is supply, not capability: no per-project artifact bundle exists until one
     * is deployed, and a shadow run on real traffic has not happened yet. This flag also gates intake,
     * so an org without it stores no SOP documents at all.
     *
     * <p>The module seeds enabled like every built-in; this flag is what keeps the rows inert, and
     * behind it the sweep is a cursor-preserving no-op until a bundle is deployed
     * ({@code conformance.ConformanceSweep}). Whether this build has the conformance code at all is a
     * separate question, answered by {@link CapabilityService}'s unavailable set.
     */
    SOP_CONFORMANCE("sop_conformance_enabled", false),

    // ---- triage ----

    /**
     * Whether Layer-2 triage runs on its own rather than on a press. Off by default because the LLM
     * escalation it drives is uncapped: automatic is an opt-in an org takes knowingly, per org,
     * through an override. That spend lands on the org's own provider credential rather than on us,
     * which makes the opt-in more clearly theirs to take and no less necessary.
     */
    TRIAGE_AUTOMATIC("triage_automatic_enabled", false);

    private final String wire;
    private final boolean defaultEnabled;

    Capability(String wire, boolean defaultEnabled) {
        this.wire = wire;
        this.defaultEnabled = defaultEnabled;
    }

    /** The flag key, which is also this capability's identifier everywhere else. Immutable. */
    public String wire() {
        return wire;
    }

    /**
     * The hosted platform default: what this capability does for an org whose tier and overrides say
     * nothing. This build's {@code CapabilityService} does not read it; see the class javadoc.
     */
    public boolean defaultEnabled() {
        return defaultEnabled;
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

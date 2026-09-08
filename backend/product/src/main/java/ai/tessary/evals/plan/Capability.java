// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import java.util.List;
import java.util.Optional;

/**
 * Something the platform can do for an organization, and the flag that decides whether it does.
 *
 * <p>There is exactly ONE axis. A capability is not "entitled by the plan and then possibly killed by a flag" —
 * that split is gone. A capability has a <b>platform default</b> declared here; a plan tier may override that
 * default (the paid overlay's plan tiers); an org's own override wins over both. Whoever is most specific
 * wins.
 *
 * <p><b>The defaults below are the HOSTED FREE TIER, not the open edition.</b> Eleven of the seventeen are
 * {@code false}, which is the right shape for a design-partner account on tessary.ai and the wrong shape for a
 * self-hosted install, where most of what is off here is open-source code the operator already has. The open
 * build therefore does not read them: {@code CapabilityService} serves everything on except the two paid
 * classifiers and {@code TRIAGE_AUTOMATIC}. These values stay because the paid entitlement engine still reads
 * them for SaaS tiers ({@code Plan#defaultFor}).
 *
 * <p>{@link #wire()} is the flag key, the capability's identifier in the API payload, and the key the SPA gates
 * on. It is deliberately ONE string rather than a name plus a derived key: two identifiers for the same thing is
 * how an override ends up pointing at a flag nobody is reading.
 *
 * <p>Adding a capability is adding a constant here with a stated default. Nothing else in the codebase learns a
 * capability's name — surfaces ask this enum.
 */
public enum Capability {

    // ---- the calibrate half: on for us, off for launch partners (decision D1) ----

    // Four constants left here with Track A: GRADERS (graders_enabled), OBSERVER (observer_enabled),
    // HUMAN_REVIEW (human_review_enabled) and AGENTIC_SYNTHESIS (agentic_synthesis_enabled). Every surface
    // they gated was deleted, so a flag for them would gate nothing. Their persisted org_feature_flag rows
    // are deleted by changeset 0016 — CapabilityController 422s a write to an unknown wire key, so an org
    // that had pinned one off would otherwise hit that on the override endpoint with no way to clear it.
    // Do not reintroduce a wire key from this list: a stored value under it means something that no
    // longer exists.

    /** Pre-merge bug prediction on PRs/branches (the CI integration). */
    CI_INTEGRATION("ci_integration_enabled", false),
    /**
     * The agentic root-cause surface: the RCA reports and the agent that writes them.
     *
     * <p>Off, and it is the one capability here whose flag exists for a <b>spend</b> reason as much as a
     * product one. Every other calibrate-half surface costs a partner nothing to look at; an RCA run is an
     * uncapped agent session a partner can start (decision D6 caps nothing). It was reachable over the API
     * with nothing in front of it — no flag existed to put there — which made "we are never surprised by
     * the bill" (segment H) rest on nobody finding the endpoint. Since #939 D4 the bill is the ORG's, not
     * ours — the session runs on the org's own credential like every other agentic lane — which moves who
     * is surprised, not whether the run needs a switch in front of it.
     *
     * <p><b>The MCP half of that paragraph is now historical.</b> This flag once gated five MCP tools
     * ({@code run_triage}, {@code get_triage}, {@code latest_triage}, {@code list_rca_reports},
     * {@code get_rca_report}); all five are gone, the MCP surface is read-only, and no tool on it is
     * RCA-gated. A report reaches an agent inlined on the case that owns it, so an org without this flag has
     * no report rows to inline and needs no gate on the read. What the flag still gates is the REST trigger
     * and the UI that calls it — the places where a run can actually be started.
     */
    RCA("rca_enabled", false),

    // ---- platform services the launch product needs ----

    /** Programmatic API access via project tokens. */
    API_ACCESS("api_access_enabled", true),
    /** Alerts — on for partners, since a case has to reach a human. The transport is a channel, and which
     *  channels an org may use is a separate question (see {@link #SLACK}). */
    ALERTS("alerts_enabled", true),
    /**
     * The whole Slack surface: the {@code slack} alert channel, the native app's outbound channel posts, and
     * the inbound {@code @mention} reply. Off — <b>Slack is not part of the launch</b>.
     *
     * <p>One flag rather than three, because the three are one thing to a person deciding whether we
     * integrate with Slack. It is deliberately NOT the same axis as {@link #ALERTS}: alerting stays on and a
     * case still reaches a human over a generic webhook, so turning Slack off costs a transport rather than
     * the capability.
     *
     * <p>Distinct from {@code evals.slack.*}, which is the DEPLOY-level switch — whether the platform has a
     * Slack app's credentials at all. That one is about us; this one is about an organization. Both must be
     * on for a Slack message to be sent, and they fail closed independently.
     */
    SLACK("slack_enabled", false),
    /** Custom PII redaction rules on top of the defaults. */
    CUSTOM_REDACTION("custom_redaction_enabled", false),

    /**
     * Whether the org may store its OWN provider credentials (Settings → Providers) and have work billed
     * to them instead of to us.
     *
     * <p><b>On, because since #939 D4 there is nothing else for a lane to run on.</b> This was {@code
     * false} from #690 until #1235, and the premise it rested on was that every launch lane is
     * platform-funded, so a stored key has nothing to spend itself on — which made launch requirement
     * H1, "partners never supply a provider key", a property of the system. D4 and D6 deleted that
     * premise: the launcher's deployment-env-var credential path, {@code ChatModelFactory}'s ambient
     * Bedrock fallback and Ollama (the one credential-free provider) are all gone, so every agentic
     * lane now resolves the ORG's own row through {@code AgenticCredentialResolver} and fails closed
     * with {@code ModelConfigError.MISSING_CREDENTIALS} when it finds none.
     *
     * <p>With this off, {@code ProviderCredentialController} refuses every write and no other code path
     * in the tree creates a credential row, so the org has no credential, Layer-2 triage cannot run on
     * any finding, no finding can become a case, and findings accumulate with a null
     * {@code triage_verdict} forever. Off therefore no longer enforces H1 — it only makes the
     * product inert — so the question this flag carries is not "may they pay" but "may they run", and
     * it has to be on wherever the classifiers are. Targeting one org off still works and still fails
     * closed the way it always did; it is a deliberate suspension rather than the shipping default.
     */
    BYO_PROVIDER_KEYS("byo_provider_keys_enabled", true),

    // ---- the classifier catalog: three on at launch, six defined and off (decisions D2/D3) ----

    /** The {@code duration_drift} built-in classifier. */
    DURATION_DRIFT("duration_drift_enabled", true),
    /** The {@code cost_drift} built-in classifier. */
    COST_DRIFT("cost_drift_enabled", true),
    /**
     * The {@code tool_error} built-in classifier. Declared and defaulted ON ahead of the detector itself: the
     * launch catalog is three rows, and a capability with no module behind it seeds nothing until segment C
     * lands one. A flag that appears the same day as its detector cannot be targeted in advance.
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
     * The {@code sop_conformance} built-in classifier. Off, and targeted on for NO org yet, ours included —
     * but no longer for either of the two reasons this note used to give. Both named enablement blockers
     * are resolved: turn-text embedding serves from the classify-service {@code /embed} endpoint
     * ({@code HttpConformanceEncoder}, the default {@code encoder-mode=http}), and intent-at-ingest ships
     * as conversation-grain intent resolution, owned by the conformance classifier (paid-only since
     * #1072; phase 0 of the sweep).
     *
     * <p>What keeps it off now is supply, not capability: no per-project artifact bundle exists until one
     * is deployed. A shadow run on real traffic has also not happened yet. This flag additionally gates
     * intake, so an org without it stores no SOP documents at all.
     *
     * <p>Both halves of that path are PAID since #842 — intake and the compile worker are
     * {@code tessary-paid/sop}, and the compiler they hand off to is {@code tessary-paid/conformance}. An
     * edition without those jars behaves exactly as an org without this flag does: bundles import, SOP
     * files are ignored, nothing is queued. The flag stays a per-ORG question either way; whether the
     * build HAS the code is {@link CapabilityService}'s unavailable set, which already names this one.
     *
     * <p>The module seeds enabled like every built-in; this flag is what keeps the rows inert, and behind
     * it the sweep is a cursor-preserving no-op until a bundle is deployed
     * ({@code conformance.ConformanceSweep}).
     */
    SOP_CONFORMANCE("sop_conformance_enabled", false),

    // ---- triage ----

    /**
     * Whether Layer-2 triage runs on its own rather than on a press. Off by default because the LLM
     * escalation it drives is uncapped (decisions D5/D6) — automatic is an opt-in an org takes knowingly,
     * per org, through an override. Since #939 D4 that spend lands on the ORG's own provider credential
     * rather than on us, which makes the opt-in more clearly theirs to take and no less necessary.
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
     * The HOSTED platform default: what this capability does for a tessary.ai org whose tier and overrides
     * say nothing. Collectively these are the hosted free tier. The OPEN edition ignores this entirely — see the
     * class javadoc and {@code CapabilityService} — so changing a value here moves the SaaS product only.
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

    /** Every capability, in declaration order — the read surface for the per-session capability payload. */
    public static List<Capability> all() {
        return List.of(values());
    }
}

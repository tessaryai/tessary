// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llmspi;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.ModelConfigError;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The distinct jobs the platform runs an LLM for, each independently pointable at a model + tier in
 * a project's settings. Before this existed, every one of these shared a single hardcoded default
 * ({@code evals.judge.default-bedrock-model-id}), so there was no way to put the high-volume grading
 * lane on a cheap model without also moving the interactive assistant onto it.
 *
 * <p>The lanes fall into two {@link LaneGroup}s, and the split is by <b>how the platform reaches the
 * model</b> rather than by what the work is for. {@link LaneGroup#LLM_CALLS} lanes are requests this
 * codebase composes and sends: we own the prompt, the tools and the response format, the call is one
 * round trip, and a Bedrock service tier rides on it. {@link LaneGroup#AGENT_VM} lanes hand an
 * inference-profile id to an agent inside an E2B microVM and never see the request — there is no
 * request of ours for a tier to attach to, and the model has to sustain a long tool-use loop over a
 * repository or a dossier, which is a capability bar the other group does not have.
 *
 * <p>That boundary also happens to be a cost/latency boundary, which is what makes it worth exposing
 * as two settings sections rather than one flat list: an {@link LaneGroup#LLM_CALLS} lane runs per
 * trace or per keystroke and is the natural home for a cheap fast model, while an
 * {@link LaneGroup#AGENT_VM} lane runs once per mover or per cause and is worth a frontier
 * one. Nobody wants to make that trade the same way in both places.
 *
 * <p><b>#939 D4: these lanes always run on the org's OWN credential now — the database is the only
 * source.</b> A javadoc note here used to claim these lanes were "platform-funded on the hosted
 * deployment", running on the platform's own ambient Bedrock identity via a deployment switch
 * ({@code evals.judge.platform-bedrock.enabled}, gating {@code ChatModelFactory#resolvePlatformBedrock}).
 * That claim was already false in this tree before D4 touched it: RCA and TRIAGE resolve their
 * model through {@code ProjectModelSettings#resolveAgenticModel} and the sandbox launcher, never
 * through {@code ChatModelFactory} at all, so the ambient-identity switch it described had zero
 * effect on either lane (confirmed by grep — nothing outside {@code ChatModelFactory}'s own dead
 * lane-resolver called it). D4 removed that dead machinery outright rather than leave a doc claim
 * describing code with no live caller. Every RCA/TRIAGE run resolves an org
 * {@code ProviderCredential} for its provider (Bedrock/mantle by default, or one of the four
 * OpenAI-compat providers) and injects it into the sandbox request — see
 * {@code AgenticCredentialResolver}. There is no platform-funded path left; Rule 10 notes this as a
 * hosted-deployment behavior change to handle at the epic 11 cutover, not a merge blocker for this
 * branch. A run that pins a model explicitly in the run modal bypasses lanes entirely and bills the
 * customer's own provider credential, exactly as before.
 */
public enum ModelLane {

    // GRADING ("grading") and SYNTHESIS ("synthesis") were here until Track A removed grading from the
    // platform, and ASSISTANT ("assistant") was here until #1117 deleted the in-app agent feature
    // entirely. 0000-baseline.sql now carries the narrowed ck_project_model_setting_lane directly:
    // the changeset that deleted ASSISTANT's project_model_setting rows and narrowed the CHECK was
    // 0018, folded into the baseline by #1144, so there is no separate file left to read.
    // fromWire throws on an unknown lane, so a stale client PUT naming any of the three now gets a
    // typed 400 rather than a silent write.

    /**
     * The agentic RCA run on a trend mover — the coding agent in an E2B microVM with the repo, the
     * evidence dossier and (optionally) live MCP reads. Long-running and once per run, so it is the
     * lane where a frontier model is most obviously worth its price.
     */
    RCA(
            "rca",
            "RCA",
            "Investigates a mover in a sandbox with your repo. Long-running, once per run.",
            LaneGroup.AGENT_VM),

    /**
     * Ruling on whether a classifier finding is a real deviation — Layer 2, one agent in a microVM
     * reading the finding's evidence dossier, plus the project's repository when it has one.
     *
     * <p>Separated from {@link #RCA} because of volume rather than shape. RCA runs when a person asks
     * about one mover; this runs once per distinct CAUSE, and once {@code triage_automatic_enabled} is
     * targeted on for an org it runs unattended over every finding that clears the recurrence bar. It is
     * platform-paid — every run books against {@code (project, triage)} in the LLM ledger, and the lane
     * can be re-pointed at a cheaper model for one project without moving RCA with it.
     *
     * <p><b>No longer uncapped, as of F4 (#994).</b> Launch decision D6 said this lane would ship with a
     * spend cap once the ledger was honest enough to enforce one against; F1-F3 made it honest and F4
     * is the cap landing. It is enforced POST-HOC, not preventively — {@code E2bTriageSandbox} checks
     * each run's actual priced cost against {@code ObserverProperties.Agentic#maxCostUsd} only after
     * the run has already completed and its usage is already booked, because no live per-turn cost
     * signal exists in this codebase to intervene on mid-run. A run over the cap is flagged (a
     * structured log line and a span attribute an operator can alert on), not rejected: see that
     * field's javadoc for why rejecting an already-paid-for ruling was considered and not chosen.
     */
    TRIAGE(
            "triage",
            "Triage",
            "Rules whether a finding is a real deviation. Runs an agent per cause, in a sandbox.",
            LaneGroup.AGENT_VM);

    private final String wire;
    private final String label;
    private final String description;
    private final LaneGroup group;

    ModelLane(String wire, String label, String description, LaneGroup group) {
        this.wire = wire;
        this.label = label;
        this.description = description;
        this.group = group;
    }

    /** The snake_case wire/DB identifier, matching {@code project_model_setting.lane}. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** Human label for the settings page. */
    public String label() {
        return label;
    }

    /** One line explaining what this lane does, so the settings row is self-explanatory. */
    public String description() {
        return description;
    }

    /** How the platform reaches the model for this lane, and the settings section it renders under. */
    public LaneGroup group() {
        return group;
    }

    /**
     * Whether a {@link ServiceTier} is meaningful for this lane. Answered by the group, because it is
     * a fact about how the platform reaches the model rather than about the job: offering a Flex
     * dropdown on an agent lane would be a control that silently does nothing, so the settings UI
     * hides it and writes get clamped to Standard (see {@code ProjectModelSettings#set}).
     */
    public boolean tiered() {
        return group.tiered();
    }

    /**
     * Whether this lane's model has to be able to drive the coding agent in a sandbox — true exactly
     * for {@link LaneGroup#AGENT_VM}. Those sandboxes ask the model to sustain a long tool-use loop
     * over a repo or a dossier, so a model that is a fine choice for every other lane can still be an
     * unrunnable one here; the settings validator rejects the pairing and the UI hides the option.
     */
    public boolean agentic() {
        return group == LaneGroup.AGENT_VM;
    }

    /**
     * Resolve a wire value, or a typed 400. Unknown lanes throw rather than defaulting: a typo'd lane
     * in a PUT that silently wrote to another lane would be a very confusing bill.
     */
    @JsonCreator
    public static ModelLane fromWire(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ModelLane l : values()) {
                if (l.wire.equals(normalized)) return l;
            }
        }
        throw new EvalsException(ModelConfigError.UNKNOWN_LANE, value);
    }
}

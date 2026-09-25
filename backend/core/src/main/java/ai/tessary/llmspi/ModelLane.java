// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The distinct jobs the platform runs an LLM for, each independently pointable at a model in a
 * project's settings.
 *
 * <p>The lanes fall into {@link LaneGroup}s, split by how the platform reaches the model rather than
 * by what the work is for. {@link LaneGroup#AGENT_VM} lanes hand an inference-profile id to an agent
 * inside an E2B microVM and never see the request: the model has to sustain a long tool-use loop over
 * a repository or a dossier, and an AGENT_VM lane runs once per mover or per cause, so it is worth a
 * frontier model. {@link LaneGroup#DECISION_CALLS} lanes ask a hosted decision model one typed
 * question per observation; the provider is the whole choice there, since each serves one decision
 * model.
 *
 * <p>These lanes always run on the org's own credential; there is no platform-funded path. Every
 * RCA/TRIAGE run resolves an org {@code ProviderCredential} for its provider (Bedrock/mantle by
 * default, or one of the four OpenAI-compat providers) and injects it into the sandbox request; see
 * {@code AgenticCredentialResolver}. A run that pins a model explicitly in the run modal bypasses
 * lanes entirely and bills the customer's own provider credential.
 */
public enum ModelLane {

    /**
     * The agentic RCA run on a trend mover: the coding agent in an E2B microVM with the repo, the
     * evidence dossier and (optionally) live MCP reads. Long-running and once per run, so it is the
     * lane where a frontier model is most obviously worth its price.
     */
    RCA(
            "rca",
            "RCA",
            "Investigates a mover in a sandbox with your repo. Long-running, once per run.",
            LaneGroup.AGENT_VM),

    /**
     * Ruling on whether a classifier finding is a real deviation: Layer 2, one agent in a microVM
     * reading the finding's evidence dossier, plus the project's repository when it has one.
     *
     * <p>Separated from {@link #RCA} because of volume rather than shape. RCA runs when a person asks
     * about one mover; this runs once per distinct cause, and once {@code triage_automatic_enabled}
     * is on for an org it runs unattended over every finding that clears the recurrence bar. Every
     * run books against {@code (project, triage)} in the LLM ledger, and the lane can be pointed at a
     * different model for one project without moving RCA with it. It offers exactly the same models
     * as RCA, in the same order, with the same defaults, so a project that never chose a triage model
     * runs whatever RCA would; picking a model priced above its provider's default is allowed, and
     * the settings page warns first.
     */
    TRIAGE(
            "triage",
            "Triage",
            "Rules whether a finding is a real deviation. Runs an agent per cause, in a sandbox.",
            LaneGroup.AGENT_VM),

    /**
     * The Frustration classifier's per-turn question to TypeSafe's Jev, direct or over OpenRouter.
     * Runs once per eligible user turn, so the price per thousand turns is what matters.
     */
    FRUSTRATION(
            "frustration",
            "Frustration",
            "Scores each eligible user turn with a decision model on your key. Runs per turn, so price per"
                    + " 1k turns is what matters.",
            LaneGroup.DECISION_CALLS);

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
     * Whether this lane's model has to be able to drive the coding agent in a sandbox, true exactly
     * for {@link LaneGroup#AGENT_VM}. Those sandboxes ask the model to sustain a long tool-use loop
     * over a repo or a dossier, so a model that is a fine choice for every other lane can still be an
     * unrunnable one here; the settings validator rejects the pairing and the UI hides the option.
     */
    public boolean agentic() {
        return group == LaneGroup.AGENT_VM;
    }

    /**
     * Whether this lane runs a hosted decision model rather than a chat model, true exactly for
     * {@link LaneGroup#DECISION_CALLS}. A decision lane takes only decision models and no other lane
     * takes one; the settings validator enforces both directions.
     */
    public boolean decision() {
        return group == LaneGroup.DECISION_CALLS;
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
        throw new TessaryException(ModelConfigError.UNKNOWN_LANE, value);
    }
}

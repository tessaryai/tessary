// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Lanes are grouped by how the platform reaches the model. Lanes run on the org's own credential, or PLATFORM when the
 * deployment supplies one. A model pinned explicitly in the run modal bypasses lanes and bills the customer's own
 * credential.
 */
public enum ModelLane {

    RCA(
            "rca",
            "RCA",
            "Investigates a mover in a sandbox with your repo. Long-running, once per run.",
            LaneGroup.AGENT_VM),

    /**
     * Separate from {@link #RCA} because of volume: it runs once per distinct cause, unattended once automatic triage
     * is on. It offers RCA's models in RCA's order.
     */
    TRIAGE(
            "triage",
            "Triage",
            "Rules whether a finding is a real deviation. Runs an agent per cause, in a sandbox.",
            LaneGroup.AGENT_VM),

    /** Started by a person, so sized like RCA. */
    AGENT_RUN(
            "agent_run",
            "Agent runs",
            "Runs an agent for an extension, once or as a live session, reading recent traces and, when the"
                    + " project has one, its repository.",
            LaneGroup.AGENT_VM),

    /** Runs once per eligible user turn, so price per thousand turns is what matters. */
    FRUSTRATION(
            "frustration",
            "Frustration",
            "Scores each eligible user turn with a decision model. Runs per turn, so price per 1k turns is what"
                    + " matters.",
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

    @JsonValue
    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public LaneGroup group() {
        return group;
    }

    /** A model that is fine elsewhere can be unrunnable here; the validator rejects the pairing. */
    public boolean agentic() {
        return group == LaneGroup.AGENT_VM;
    }

    /** The validator enforces both directions. */
    public boolean decision() {
        return group == LaneGroup.DECISION_CALLS;
    }

    /**
     * Unknown lanes throw rather than default: a typo'd lane silently writing another lane would be a confusing bill.
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

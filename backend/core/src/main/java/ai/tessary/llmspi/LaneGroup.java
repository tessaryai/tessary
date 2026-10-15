// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the platform reaches the model for a {@link ModelLane}: an agent in a microVM, or typed questions to a hosted
 * decision model. That decides what the settings surface offers per lane.
 */
public enum LaneGroup {

    /**
     * There is no request of ours for a tier to ride on, and a model that cannot sustain a multi-turn tool-use loop is
     * unrunnable here.
     */
    AGENT_VM(
            "agent_vm",
            "Agent in a VM",
            "A model id handed to an agent running in a sandbox, which drives it from the inside. The"
                    + " agent composes every request, so there is no tier and no effort for us to set — only"
                    + " which model it gets."),

    /**
     * Not a chat completion: goes through {@code llm/decisions/}. Each provider serves one decision model, so the
     * provider is the whole choice.
     */
    DECISION_CALLS(
            "decision_calls",
            "Decision models",
            "One question per turn, answered by a decision model on your key. No tier, no effort, one"
                    + " model per provider.");

    private final String wire;
    private final String label;
    private final String description;

    LaneGroup(String wire, String label, String description) {
        this.wire = wire;
        this.label = label;
        this.description = description;
    }

    /** So the frontend groups on a value, not a label. */
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

    /** False for {@link #DECISION_CALLS}: each provider serves one decision model. */
    public boolean modelSelectable() {
        return this != DECISION_CALLS;
    }
}

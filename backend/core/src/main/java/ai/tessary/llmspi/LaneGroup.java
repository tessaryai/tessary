// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the platform reaches the model for a {@link ModelLane}: it hands a model id to an agent running
 * in a microVM and never sees the request at all, or asks a hosted decision model one typed question
 * per observation.
 *
 * <p>That one fact decides everything the settings surface needs to know per lane — whether the model
 * has to be able to sustain a long tool-use loop, and which models are offerable — so it is named once
 * here rather than re-derived at each call site. The settings page renders one section per group.
 */
public enum LaneGroup {

    /**
     * The platform passes an inference-profile id into an E2B microVM and an agent drives the model
     * from inside it. There is no request of ours for a tier to ride on, and a model that cannot
     * sustain a multi-turn tool-use loop is unrunnable here.
     */
    AGENT_VM(
            "agent_vm",
            "Agent in a VM",
            "A model id handed to an agent running in a sandbox, which drives it from the inside. The"
                    + " agent composes every request, so there is no tier and no effort for us to set — only"
                    + " which model it gets."),

    /**
     * The platform asks a hosted decision model (TypeSafe's Jev) a typed question and reads back
     * probabilities. Not a chat completion: the call goes through {@code llm/decisions/}, never
     * {@code ChatModel}, and each provider serves exactly one decision model, so the provider is the
     * whole choice.
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

    /** The snake_case wire identifier, so the frontend groups on a value rather than on a label. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The section heading on the settings page. */
    public String label() {
        return label;
    }

    /** The line under that heading, explaining what the lanes below it have in common. */
    public String description() {
        return description;
    }

    /**
     * Whether a lane in this group offers a choice of model within a provider. False for
     * {@link #DECISION_CALLS}: each provider serves one decision model, so the settings page shows a
     * provider select and nothing else.
     */
    public boolean modelSelectable() {
        return this != DECISION_CALLS;
    }
}

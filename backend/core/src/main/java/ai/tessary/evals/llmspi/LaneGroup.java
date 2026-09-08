// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llmspi;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the platform reaches the model for a {@link ModelLane}: it either builds the Bedrock request
 * itself, or hands a model id to an agent running in a microVM and never sees the request at all.
 *
 * <p>That one fact decides everything the settings surface needs to know per lane — whether a service
 * tier is a meaningful control, whether the model has to be able to sustain a long tool-use loop, and
 * which models are offerable — so it is named once here rather than re-derived at each call site from
 * a pair of booleans that happen to agree. The settings page renders one section per group.
 */
public enum LaneGroup {

    /**
     * The platform composes and sends the request. Prompt, tools and response format are ours, the
     * call is one round trip, and a Bedrock service tier rides on it.
     */
    LLM_CALLS(
            "llm_calls",
            "LLM calls",
            "Requests Tessary builds and sends. Each is one round trip, so the service tier and the"
                    + " reasoning effort are ours to choose, and these lanes run often enough that the price"
                    + " of the model is the thing that matters most."),

    /**
     * The platform passes an inference-profile id into an E2B microVM and an agent drives the model
     * from inside it. There is no request of ours for a tier to ride on, and a model that cannot
     * sustain a multi-turn tool-use loop is unrunnable here whatever it does in the other group.
     */
    AGENT_VM(
            "agent_vm",
            "Agent in a VM",
            "A model id handed to an agent running in a sandbox, which drives it from the inside. The"
                    + " agent composes every request, so there is no tier and no effort for us to set — only"
                    + " which model it gets.");

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
     * Whether a {@link ServiceTier} is a real choice for this group's lanes. A tier is a property of a
     * request, so only the group whose requests we build has one; offering the control on the other
     * would be a stored preference that never reaches a wire.
     */
    public boolean tiered() {
        return this == LLM_CALLS;
    }

    /**
     * Whether a reasoning effort is a real choice for this group's lanes — separate from
     * {@link #tiered} because it is a separate control with a separate consequence, and it happens to
     * follow from the same fact: the effort rides on the request, and for {@link #AGENT_VM} the
     * request is composed inside the microVM by the agent, which never sees ours.
     *
     * <p>This is a gate on top of the model's own {@code effort_levels}, not a replacement for it —
     * GPT-5.6 Terra accepts six levels and is offered on {@link #AGENT_VM}, so without this the
     * settings page would show a control for it that changed nothing.
     */
    public boolean effortTunable() {
        return this == LLM_CALLS;
    }
}

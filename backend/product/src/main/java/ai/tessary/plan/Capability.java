// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.util.List;
import java.util.Optional;

/**
 * One axis: this build's default, overridden per org. {@link #wire()} is the single identifier (flag key, API key, SPA
 * gate), since two identifiers is how an override ends up on a flag nobody reads.
 */
public enum Capability {

    // Retired wire keys that must not return: graders_enabled, observer_enabled, human_review_enabled,
    // agentic_synthesis_enabled, behavior_drift_enabled, sop_conformance_enabled.

    CI_INTEGRATION("ci_integration_enabled"),
    /**
     * An RCA run is an uncapped agent session, so this is a spend decision too. It gates the trigger and UI, not reads.
     */
    RCA("rca_enabled"),

    API_ACCESS("api_access_enabled"),
    /** Off by default in this build. */
    ALERTS("alerts_enabled"),
    /**
     * Independent of {@code tessary.slack.*}, the deployment's credentials switch; both must be on, and each fails
     * closed.
     */
    SLACK("slack_enabled"),
    CUSTOM_REDACTION("custom_redaction_enabled"),

    /**
     * Every agentic lane fails closed without the org's own credential, so turning this off makes the product inert
     * rather than protecting spend.
     */
    BYO_PROVIDER_KEYS("byo_provider_keys_enabled"),

    DURATION_DRIFT("duration_drift_enabled"),
    COST_DRIFT("cost_drift_enabled"),
    TOOL_ERROR("tool_error_enabled"),
    FRUSTRATION("frustration_enabled"),
    GROUNDEDNESS("groundedness_enabled"),
    SECRET_LEAK("secret_leak_enabled"),
    MALFORMED_OUTPUT("malformed_output_enabled"),

    /** Off by default because the escalation it drives is uncapped: automatic is an opt-in an org takes knowingly. */
    TRIAGE_AUTOMATIC("triage_automatic_enabled"),

    /** On by default. Gates the Prompts page, /v1/prompts, the two prompt MCP tools, and the playground. */
    PROMPT_MANAGEMENT("prompt_management_enabled");

    private final String wire;

    Capability(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<Capability> fromWire(String value) {
        for (Capability c : values()) {
            if (c.wire.equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public static List<Capability> all() {
        return List.of(values());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Runs one Layer-2 triage ruling in an environment that can materialize a finding's dossier, run the
 * agent against it and against this platform's MCP surface, and tear down. {@link E2bTriageSandbox}
 * drives an E2B microVM via the launcher sidecar. Selected by {@code tessary.classifier.triage-sandbox}.
 */
public interface TriageSandbox {

    /** Selector key, matched against {@code tessary.classifier.triage-sandbox}. */
    String key();

    /**
     * Run the agent over the finding's dossier. Empty means the run happened and produced nothing
     * usable — {@link BehaviorTriageEngine} turns that into a thrown {@code TRIAGE_RUN_INCOMPLETE} so
     * the job retries. A launcher-level failure (unreachable, rejected credentials, no route) throws
     * {@code TRIAGE_LAUNCHER_UNAVAILABLE} instead: it is not about this finding and will be just as
     * true for the next one. See {@link E2bTriageSandbox}'s class javadoc for the full split.
     */
    Optional<SandboxRun> run(SandboxRequest req);

    /**
     * @param projectId the project being ruled on, for trace and cost attribution
     * @param findingId the cause under triage (trace metadata only)
     * @param files the finding's dossier, relative path → content, written under the sandbox's
     *     {@code dossier/} directory
     * @param prompt the ruling task handed to the agent
     * @param jsonSchema schema the agent's final answer is constrained to
     * @param mcpUrl this platform's MCP endpoint, the agent's only door to the substrate
     * @param mcpToken short-lived project-scoped key — sent to the launcher, never logged
     * @param systemPrompt the triage agent's system prompt, replacing the provider default for
     *     this run; {@code null} runs the sandbox exactly as it did before this field existed
     *     (no custom agent, no MCP relay). {@link BehaviorTriageEngine} always sends its one
     *     shared {@code SYSTEM_PROMPT} here — {@code null} survives only as a signature this
     *     interface's other implementers (or a future caller with nothing agent-specific to send)
     *     may still take.
     */
    record SandboxRequest(
            String projectId,
            String findingId,
            Map<String, String> files,
            String prompt,
            String jsonSchema,
            String mcpUrl,
            String mcpToken,
            @Nullable String systemPrompt) {}

    /** The agent's run: {@code resultText} is its final message (the schema-constrained JSON). */
    record SandboxRun(String resultText) {}
}

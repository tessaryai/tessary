// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import java.util.Map;
import java.util.Optional;

/**
 * Runs one Layer-2 triage ruling in an environment that can materialize a finding's dossier, run the
 * agent against it and against this platform's MCP surface, and tear down. {@link E2bTriageSandbox}
 * drives an E2B microVM via the launcher sidecar. Selected by {@code evals.classifier.triage-sandbox},
 * mirroring the observer's {@code AnalysisSandbox}/{@code evals.observer.agentic.sandbox}.
 */
public interface TriageSandbox {

    /** Selector key, matched against {@code evals.classifier.triage-sandbox}. */
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
     */
    record SandboxRequest(
            String projectId,
            String findingId,
            Map<String, String> files,
            String prompt,
            String jsonSchema,
            String mcpUrl,
            String mcpToken) {}

    /** The agent's run: {@code resultText} is its final message (the schema-constrained JSON). */
    record SandboxRun(String resultText) {}
}

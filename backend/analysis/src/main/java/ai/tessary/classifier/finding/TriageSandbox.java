// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.agentrun.StepWords;
import java.util.Map;
import java.util.Optional;

/** Runs one Layer-2 triage ruling; selected by {@code tessary.classifier.triage-sandbox}. */
public interface TriageSandbox {

    String key();

    /**
     * Empty means the run produced nothing usable, which becomes {@code TRIAGE_RUN_INCOMPLETE} and a retry. A
     * launcher-level failure throws {@code TRIAGE_LAUNCHER_UNAVAILABLE}, since it applies to every finding.
     */
    Optional<SandboxRun> run(SandboxRequest req);

    /**
     * @param mcpUrl the agent's only door to the substrate @param mcpToken sent to the launcher, never logged @param
     * runId the {@code agent_run} the steps are written to @param words how the steps word the finding's evidence
     */
    record SandboxRequest(
            String projectId,
            String findingId,
            Map<String, String> files,
            String prompt,
            String jsonSchema,
            String mcpUrl,
            String mcpToken,
            String systemPrompt,
            String runId,
            StepWords.Context words) {}

    /** {@code resultText} is the schema-constrained JSON. */
    record SandboxRun(String resultText) {}
}

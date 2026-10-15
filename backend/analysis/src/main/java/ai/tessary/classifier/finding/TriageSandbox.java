// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
     * systemPrompt replaces the provider default; {@code null} runs without the custom agent or MCP relay
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

    /** {@code resultText} is the schema-constrained JSON. */
    record SandboxRun(String resultText) {}
}

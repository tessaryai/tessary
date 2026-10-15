// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One coding-agent session in a fresh sandbox. Unlike the triage and RCA sandboxes it resolves nothing itself: model,
 * provider and credential arrive resolved on the {@link Request}, from {@link AgentRunService}.
 */
public interface AgentRunSandbox {

    /** Selector key, matched against {@code tessary.agentrun.sandbox}: {@code e2b} or {@code docker}. */
    String key();

    /**
     * Throws {@code TessaryException} with an {@code AgentRunError}: {@code LAUNCHER_UNAVAILABLE},
     * {@code LAUNCHER_MISCONFIGURED} (a retry cannot help), {@code RUN_FAILED}, {@code BAD_OUTPUT} or
     * {@code TIMED_OUT}.
     */
    Result run(Request req);

    /**
     * @param files relative path to content, written under the sandbox's {@code dossier/} @param jsonSchema null lets
     * the agent answer in prose @param cloneUrl a secret, never logged; null for no repository @param headSha present
     * exactly when {@code cloneUrl} is @param modelCost models.dev rates declared to OpenCode; null leaves the run
     * unpriced @param credential sent to the launcher, never logged or filed @param mcpToken short-lived project-scoped
     * key; sent to the launcher, never logged
     */
    record Request(
            String projectId,
            String lane,
            String systemPrompt,
            String prompt,
            Map<String, String> files,
            @Nullable String jsonSchema,
            @Nullable String cloneUrl,
            @Nullable String headSha,
            String model,
            ModelsDevRates.@Nullable ModelCost modelCost,
            String provider,
            AgenticCredentialResolver.Credential credential,
            String mcpUrl,
            String mcpToken,
            long timeoutMs,
            int maxTurns,
            LlmUsageAccountant.Subject subject) {}

    /** @param structuredOutput null when the run carried no schema @param resultText null when blank */
    record Result(
            @Nullable JsonNode structuredOutput, @Nullable String resultText, int turns, long durationMs) {}
}

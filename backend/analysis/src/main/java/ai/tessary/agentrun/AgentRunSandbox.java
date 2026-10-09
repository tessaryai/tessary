// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One coding-agent session in a fresh sandbox, over whatever a caller hands it: a system prompt, a
 * task, a set of files, optionally a schema the answer must satisfy and the project's repository.
 * The agent reads the platform through the MCP door the request carries. {@link E2bAgentRunSandbox}
 * drives the launcher sidecar; a sandbox is selected by {@code tessary.agentrun.sandbox} matching
 * {@link #key()}, as {@code TriageSandbox} is.
 *
 * <p>Unlike the triage and RCA sandboxes this one resolves nothing itself: the model, provider and
 * credential arrive already resolved on the {@link Request}, so the ceremony around a run lives in
 * one place, {@link AgentRunService}, and a caller never repeats it.
 */
public interface AgentRunSandbox {

    /** Selector key, matched against {@code tessary.agentrun.sandbox}: {@code e2b} or {@code docker}. */
    String key();

    /**
     * Run the agent. Throws {@code TessaryException} carrying an {@code AgentRunError}:
     * {@code LAUNCHER_UNAVAILABLE} when the launcher could not be reached or failed before the run
     * started, {@code LAUNCHER_MISCONFIGURED} when it refused the request on terms a retry cannot
     * change, {@code RUN_FAILED} when this run failed on its own, {@code BAD_OUTPUT} when the agent
     * finished with nothing usable, and {@code TIMED_OUT} when the run hit its wall clock.
     */
    Result run(Request req);

    /**
     * @param projectId the project the run is for, for trace and cost attribution
     * @param lane the {@code ModelLane} wire value the run books against
     * @param systemPrompt the agent's system prompt, replacing the provider default
     * @param prompt the task
     * @param files relative path to content, written under the sandbox's {@code dossier/} directory
     * @param jsonSchema schema the final answer is constrained to; null lets the agent answer in prose
     * @param cloneUrl authenticated clone URL of the project's repository, or null for none; a secret,
     *     never logged
     * @param headSha the commit to check out, present exactly when {@code cloneUrl} is
     * @param model the model id the agent runs, and the name the ledger row stores
     * @param pricingId the id the run is priced under; equal to {@code model} except on the catalog
     *     providers whose price-book keys carry a route prefix (see {@code ModelCatalog#pricingId})
     * @param provider the {@code ModelProvider} wire value the credential belongs to
     * @param credential the org's decrypted credential; sent to the launcher, never logged or filed
     * @param mcpUrl this platform's MCP endpoint
     * @param mcpToken short-lived project-scoped key; sent to the launcher, never logged
     * @param timeoutMs the run's wall clock
     * @param maxTurns the agent's turn budget
     * @param subject what the spend is for, booked on the ledger row
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
            String pricingId,
            String provider,
            AgenticCredentialResolver.Credential credential,
            String mcpUrl,
            String mcpToken,
            long timeoutMs,
            int maxTurns,
            LlmUsageAccountant.Subject subject) {}

    /**
     * @param structuredOutput the schema-constrained object the sandbox extracted and validated, or
     *     null when the run carried none
     * @param resultText the agent's final reply as text, or null when blank
     * @param turns how many turns the agent took
     * @param durationMs wall-clock time of the launcher round trip
     */
    record Result(
            @Nullable JsonNode structuredOutput, @Nullable String resultText, int turns, long durationMs) {}
}

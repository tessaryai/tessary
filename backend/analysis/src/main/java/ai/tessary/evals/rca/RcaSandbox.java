// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs one agentic RCA investigation in an environment that can clone the target repo, materialize
 * the finding's evidence dossier, and execute the agent over it. {@link E2bRcaSandbox} drives an E2B
 * microVM via the launcher sidecar. Selected by {@code evals.rca.agentic.sandbox}, mirroring the
 * observer's {@code AnalysisSandbox}/{@code evals.observer.agentic.sandbox}.
 */
public interface RcaSandbox {

    /** Selector key, matched against {@code evals.rca.agentic.sandbox}. */
    String key();

    /**
     * Run the agent over the finding's evidence and (when the project has one) its repository. Unlike
     * {@code AnalysisSandbox}'s fail-open contract, this always either returns a completed run or
     * throws {@link ai.tessary.evals.open.errors.EvalsException} — a user-triggered RCA must stamp
     * {@code failed} rather than silently degrade to a bogus "inconclusive" verdict.
     */
    SandboxRun run(SandboxRequest req);

    /**
     * @param projectId the project being analyzed (for trace/cost attribution)
     * @param subjectId the mover subject (trace metadata only)
     * @param cloneUrl authenticated clone URL — embeds a short-lived token, never logged. Null for a
     *     project with no repository connected: the run is evidence-only and the sandbox skips the
     *     clone entirely (see {@code AgenticRcaEngine} on the ceiling that lowers)
     * @param headSha commit to check out; null with {@code cloneUrl}
     * @param files evidence dossier, relative path → content, written under the sandbox dossier dir
     * @param prompt the analysis task handed to the agent
     * @param jsonSchema schema the agent's final output is constrained to
     * @param mcpUrl platform API base for live MCP reads
     * @param mcpToken short-lived MCP key — sent to the launcher, never logged
     * @param reportId the {@code rca_report} row this run is investigating — F2's ledger subject, so
     *     an RCA's cost is attributable to the report it was spent on, the same way a triage ruling's
     *     cost is attributed to the finding ({@code E2bTriageSandbox.SUBJECT_KIND}). Never null: every
     *     agentic RCA run is triggered against an already-created report row (see {@code
     *     AgenticRcaEngine#run}).
     */
    record SandboxRequest(
            String projectId,
            String subjectId,
            @Nullable String cloneUrl,
            @Nullable String headSha,
            Map<String, String> files,
            String prompt,
            String jsonSchema,
            @Nullable String mcpUrl,
            @Nullable String mcpToken,
            String reportId) {}

    /** The agent's run: {@code resultText} is its final message (the schema-constrained JSON). */
    record SandboxRun(String resultText) {}
}

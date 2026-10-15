// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Runs one agentic RCA investigation; selected by {@code tessary.rca.agentic.sandbox}. */
public interface RcaSandbox {

    String key();

    /**
     * Not fail-open: returns a completed run or throws, so a user-triggered RCA stamps {@code failed} rather than a
     * bogus "no cause found".
     */
    SandboxRun run(SandboxRequest req);

    /**
     * @param cloneUrl embeds a short-lived token, never logged; null means an evidence-only run with no clone @param
     * headSha null with {@code cloneUrl} @param onsetAt the sandbox resolves the commit live at the onset; null with
     * {@code cloneUrl} @param mcpToken sent to the launcher, never logged @param reportId the ledger subject, so an
     * RCA's cost is attributable to its report; never null
     */
    record SandboxRequest(
            String projectId,
            String subjectId,
            @Nullable String cloneUrl,
            @Nullable String headSha,
            @Nullable String onsetAt,
            Map<String, String> files,
            String prompt,
            String jsonSchema,
            String mcpUrl,
            String mcpToken,
            String reportId) {}

    /** {@code resultText} is the schema-constrained JSON. */
    record SandboxRun(String resultText) {}
}

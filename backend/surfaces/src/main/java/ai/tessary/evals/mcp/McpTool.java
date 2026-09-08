// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.mcp;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.plan.Capability;
import java.util.Map;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * One MCP tool: name + human-readable description + JSON Schema for inputs + handler, plus the
 * capability an org must hold to be offered it.
 *
 * <p>Handlers receive the per-request {@link TenantContext} (so they can scope
 * reads/writes to the bound project) and the parsed {@code arguments} map from
 * the {@code tools/call} request. They return any Jackson-serialisable object.
 * Throw {@link ToolException} to signal an application-level failure that gets
 * reported as a tool-call error (not a JSON-RPC error).</p>
 *
 * <p><b>{@code capability} is on the tool, not in a table beside it.</b> A second list of which tools are
 * gated is a list that drifts from the tools — the exact failure segment F found between the command
 * palette's settings list and the settings rail's own. Declaring it here means a new tool cannot be added
 * without its author answering the question.
 *
 * <h2>Where the line falls</h2>
 *
 * <p>The gate had drifted into meaning two different things: {@code list_call_sites} was open while
 * {@code list_failure_modes} sat behind {@code GRADERS}, though both are plain reads of the same imported
 * bundle — so a partner could see the call sites and not the failure taxonomy that makes them legible. One
 * rule now, and new tools are placed by it:
 *
 * <ul>
 *   <li><b>Open</b> — what the launch product itself produces or consumes: the bound project, the imported
 *       taxonomy (call sites, failure modes, quality dimensions), cases and the RCA reports they carry,
 *       classifier findings, the query datasets, and the substrate reads. A partner's own traffic and the
 *       cases raised on it are the product, not an upsell.</li>
 *   <li><b>{@code GRADERS}</b> — {@code list_graders} and {@code get_grader}, the only two gated tools.
 *       Both are reads; what sits behind the gate is the calibrate half's <i>content</i>, an authored and
 *       curated grader set, not a privileged action.</li>
 * </ul>
 *
 * <p>{@code Capability.RCA} gates nothing here any more. It used to gate five tools, and the reason was
 * spend: {@code run_triage} started a platform-paid agent session. Now that a report reaches MCP inlined on
 * the case that owns it, an org without RCA simply has no report rows to inline — the gate moved from the
 * tool to the data, which is where it was always more honestly enforced.
 *
 * <p><b>There are no write tools, and adding one is a product decision rather than a registration.</b> Every
 * tool in the catalogue reads; nothing writes a row, spends a token, or starts an agent run, and
 * {@code initialize} states that to the caller as a property of the surface. The writes that exist —
 * resolving or absorbing a case, accepting a grader edit, triggering an RCA — each record a human judgement
 * or spend the platform's money, and each is reachable over REST from the UI where a person is the one
 * asking. So a new tool that writes is not "one more {@code add(...)} call": it is a decision that an agent
 * may act on this project, and it invalidates a sentence this server tells every client on connect.
 *
 * <p>An open tool may still return an id whose tool is gated — a quality dimension names its
 * {@code grader_id}. That is correct and not a leak: the id is a reference, and the tool that would resolve
 * it reads as unknown for an org that is not offered it.
 *
 * @param capability the capability required to see and call this tool; {@code null} means every org gets
 *     it, which is the right answer for the launch product's own tools.
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        BiFunction<TenantContext, Map<String, Object>, Object> handler,
        @Nullable Capability capability) {

    /** An ungated tool — one the launch product itself supports. */
    public McpTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            BiFunction<TenantContext, Map<String, Object>, Object> handler) {
        this(name, description, inputSchema, handler, null);
    }

    /** Thrown by tool handlers to signal a user-facing error. */
    public static final class ToolException extends RuntimeException {
        public ToolException(String message) {
            super(message);
        }

        public ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

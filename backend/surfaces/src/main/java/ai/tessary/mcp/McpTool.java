// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import ai.tessary.plan.Capability;
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
 * <p>{@code capability} is on the tool, not in a table beside it: a second list of which tools are
 * gated is a list that drifts from the tools. Declaring it here means a new tool cannot be added
 * without its author answering the question. No tool on this surface currently declares one; the
 * whole catalogue reads and is offered to every org.
 *
 * <p>There are no write tools, and adding one is a product decision rather than a registration.
 * Every tool in the catalogue reads; nothing writes a row, spends a token, or starts an agent run,
 * and {@code initialize} states that to the caller as a property of the surface. The writes that
 * exist elsewhere in the product (resolving or absorbing a case, triggering an RCA run) each
 * record a human judgement or spend the platform's money, and each is reachable over REST from the
 * UI where a person is the one asking. So a new tool that writes is not "one more {@code add(...)}
 * call": it is a decision that an agent may act on this project, and it invalidates a sentence this
 * server tells every client on connect.
 *
 * @param capability the capability required to see and call this tool; {@code null} means every org
 *     gets it, which is the case for every tool this surface currently offers.
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        BiFunction<TenantContext, Map<String, Object>, Object> handler,
        @Nullable Capability capability) {

    /** An ungated tool: one the launch product itself supports. */
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * One MCP tool: name + human-readable description + JSON Schema for inputs + handler. Every tool is
 * offered to every org.
 *
 * <p>Handlers receive the per-request {@link TenantContext} (so they can scope
 * reads/writes to the bound project) and the parsed {@code arguments} map from
 * the {@code tools/call} request. They return any Jackson-serialisable object.
 * Throw {@link ToolException} to signal an application-level failure that gets
 * reported as a tool-call error (not a JSON-RPC error).</p>
 *
 * <p>There are no write tools, and adding one is a product decision rather than a registration.
 * Every tool in the catalogue reads; nothing writes a row, spends a token, or starts an agent run,
 * and {@code initialize} states that to the caller as a property of the surface. The writes that
 * exist elsewhere in the product (resolving or absorbing a case, triggering an RCA run) each
 * record a human judgement or spend the platform's money, and each is reachable over REST from the
 * UI where a person is the one asking. So a new tool that writes is not "one more {@code add(...)}
 * call": it is a decision that an agent may act on this project, and it invalidates a sentence this
 * server tells every client on connect.
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        BiFunction<TenantContext, Map<String, Object>, Object> handler) {

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

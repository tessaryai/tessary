// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * One MCP tool, offered to every org. Throw {@link ToolException} to report a tool-call error rather than a JSON-RPC
 * error. Every tool reads only, and {@code initialize} tells clients so; adding a write tool is a product decision that
 * changes that promise, not one more {@code add(...)} call.
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        BiFunction<TenantContext, Map<String, Object>, Object> handler) {

    public static final class ToolException extends RuntimeException {
        public ToolException(String message) {
            super(message);
        }

        public ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

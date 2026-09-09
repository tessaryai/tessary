// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire records for the JSON-RPC 2.0 envelope MCP uses.
 *
 * <p>We accept either a numeric id, string id, or null id. Jackson maps it as a raw
 * {@link JsonNode} so we can echo it back verbatim in the response — the client
 * uses byte-identical id matching.</p>
 *
 * <p>Notifications are requests with a null id; we never produce notifications
 * from the server today, but we tolerate them on the inbound path (just no reply).</p>
 */
public final class JsonRpc {

    private JsonRpc() {}

    public record Request(
            String jsonrpc,
            @Nullable JsonNode id,
            @Nullable String method,
            @Nullable JsonNode params) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String jsonrpc,
            JsonNode id,
            @Nullable Object result,
            @Nullable ErrorBody error) {
        public static Response ok(JsonNode id, Object result) {
            return new Response("2.0", id, result, null);
        }

        public static Response err(JsonNode id, int code, @Nullable String message, @Nullable Object data) {
            return new Response("2.0", id, null, new ErrorBody(code, message, data));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            int code, @Nullable String message, @Nullable Object data) {}

    // Canonical JSON-RPC 2.0 error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /**
     * Helper for endpoints that need to read tool-call params as an untyped map
     * without forcing every tool to declare a record.
     */
    public static Map<String, Object> emptyParams() {
        return Map.of();
    }
}

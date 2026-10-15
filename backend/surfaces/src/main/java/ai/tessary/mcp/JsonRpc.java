// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC 2.0 wire records for MCP. The id is kept as a raw {@link JsonNode} so it echoes back verbatim, since clients
 * match ids byte for byte. Inbound notifications (null id) get no reply.
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

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}

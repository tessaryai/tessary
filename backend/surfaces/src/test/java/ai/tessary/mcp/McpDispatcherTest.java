// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol-level behaviour of {@link McpDispatcher}. The contract here is
 * the MCP wire — if we break the distinction between JSON-RPC errors (protocol)
 * and tool errors (application), clients can't tell whether to retry or surface.
 */
class McpDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        McpTool echo = new McpTool(
                "echo", "returns its args", Map.of("type", "object"), (ctx, args) -> Map.of("echoed", args));
        McpTool boom = new McpTool("boom", "always errors", Map.of("type", "object"), (ctx, args) -> {
            throw new McpTool.ToolException("nope");
        });
        McpTool kaboom = new McpTool("kaboom", "throws unexpectedly", Map.of("type", "object"), (ctx, args) -> {
            throw new RuntimeException("bug");
        });
        var registry = new McpToolRegistry(List.of(echo, boom, kaboom));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    private JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
        return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
    }

    private TenantContext ctx() {
        return new TenantContext("u", "u@x", "o", "p", "member", null);
    }

    /** Asserts the dispatch produced a response (most methods do) and returns it non-null. */
    private static JsonRpc.Response require(JsonRpc.@Nullable Response r) {
        assertNotNull(r);
        return Objects.requireNonNull(r);
    }

    private static JsonRpc.ErrorBody error(JsonRpc.@Nullable Response r) {
        return Objects.requireNonNull(require(r).error());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultMap(JsonRpc.@Nullable Response r) {
        return (Map<String, Object>) Objects.requireNonNull(require(r).result());
    }

    @Test
    void initialize_echoesClientProtocolVersion() throws Exception {
        JsonNode params = mapper.readTree("{\"protocolVersion\":\"2099-01-01\",\"capabilities\":{}}");
        JsonRpc.Response r = require(dispatcher.dispatch(req(1, "initialize", params), ctx()));
        assertNull(r.error());
        Map<String, Object> result = resultMap(r);
        assertEquals("2099-01-01", result.get("protocolVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) Objects.requireNonNull(result.get("capabilities"));
        assertNotNull(caps.get("tools"));
    }

    @Test
    void rejectsBadJsonrpcField() {
        JsonRpc.Request bad = new JsonRpc.Request("1.0", IntNode.valueOf(1), "ping", null);
        assertEquals(
                JsonRpc.INVALID_REQUEST, error(dispatcher.dispatch(bad, ctx())).code());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() {
        assertEquals(
                JsonRpc.METHOD_NOT_FOUND,
                error(dispatcher.dispatch(req(1, "nope", null), ctx())).code());
    }

    @Test
    void notificationReturnsNullResponse() {
        // id = null → notification per JSON-RPC 2.0
        JsonRpc.Request n = new JsonRpc.Request("2.0", NullNode.getInstance(), "notifications/initialized", null);
        JsonRpc.Response r = dispatcher.dispatch(n, ctx());
        assertNull(r, "notifications must not produce a response envelope");
    }

    @Test
    void notification_unknownMethod_stillSilent() {
        // unknown methods that arrive as notifications shouldn't emit JSON-RPC errors.
        JsonRpc.Request n = new JsonRpc.Request("2.0", NullNode.getInstance(), "notifications/whatever", null);
        assertNull(dispatcher.dispatch(n, ctx()));
    }

    @Test
    void toolsList_includesEveryRegisteredTool() {
        Map<String, Object> result = resultMap(dispatcher.dispatch(req(1, "tools/list", null), ctx()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) Objects.requireNonNull(result.get("tools"));
        assertEquals(3, tools.size());
    }

    @Test
    void toolsCall_missingParams_reportsInvalidParams() {
        assertEquals(
                JsonRpc.INVALID_PARAMS,
                error(dispatcher.dispatch(req(1, "tools/call", null), ctx())).code());
    }

    @Test
    void toolsCall_unknownTool_isToolErrorNotProtocolError() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"does-not-exist\",\"arguments\":{}}");
        JsonRpc.Response r = require(dispatcher.dispatch(req(1, "tools/call", params), ctx()));
        // Spec: unknown tool is reported INSIDE the result with isError=true,
        // not as a JSON-RPC error. Clients use this to decide retry vs UI surface.
        assertNull(r.error());
        assertEquals(Boolean.TRUE, resultMap(r).get("isError"));
    }

    @Test
    void toolsCall_toolThrowsToolException_isToolError() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"boom\",\"arguments\":{}}");
        JsonRpc.Response r = require(dispatcher.dispatch(req(1, "tools/call", params), ctx()));
        assertNull(r.error());
        Map<String, Object> result = resultMap(r);
        assertEquals(Boolean.TRUE, result.get("isError"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        assertTrue(Objects.requireNonNull(content.get(0).get("text")).toString().contains("nope"));
    }

    @Test
    void toolsCall_toolThrowsRuntime_isProtocolError() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"kaboom\",\"arguments\":{}}");
        // Unexpected exceptions ARE protocol errors — caller can't recover by retrying input.
        assertEquals(
                JsonRpc.INTERNAL_ERROR,
                error(dispatcher.dispatch(req(1, "tools/call", params), ctx())).code());
    }

    @Test
    void toolsCall_argumentsNonObject_isInvalidParams() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"echo\",\"arguments\":\"not-an-object\"}");
        assertEquals(
                JsonRpc.INVALID_PARAMS,
                error(dispatcher.dispatch(req(1, "tools/call", params), ctx())).code());
    }

    @Test
    void toolsCall_argumentsNull_callsHandlerWithEmptyMap() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"echo\",\"arguments\":null}");
        JsonRpc.Response r = require(dispatcher.dispatch(req(1, "tools/call", params), ctx()));
        assertNull(r.error());
        Map<String, Object> result = resultMap(r);
        assertEquals(Boolean.FALSE, result.get("isError"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) Objects.requireNonNull(result.get("structuredContent"));
        assertEquals(Map.of(), structured.get("echoed"));
    }

    @Test
    void pingReturnsEmptyMap() {
        JsonRpc.Response r = require(dispatcher.dispatch(req(1, "ping", null), ctx()));
        assertNull(r.error());
        assertEquals(Map.of(), r.result());
    }
}

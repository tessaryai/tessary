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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void notification_unknownMethod_stillSilent() {
        // unknown methods that arrive as notifications shouldn't emit JSON-RPC errors.
        JsonRpc.Request n = new JsonRpc.Request("2.0", NullNode.getInstance(), "notifications/whatever", null);
        assertNull(dispatcher.dispatch(n, ctx()));
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

    /**
     * A tool result Jackson cannot pretty-print still comes back as a successful tool result, its text block
     * falling back to the value's own string form. Letting the serialisation failure escape would turn a tool
     * that answered into a -32603 the client reads as a server fault.
     */
    @Test
    void toolsCall_aResultJacksonCannotRenderStillReturnsTheResult() throws Exception {
        Object opaque = new Object() {
            @Override
            public String toString() {
                return "opaque result";
            }
        };
        McpTool opaqueTool = new McpTool("opaque", "returns a bean with no properties", Map.of(), (c, a) -> opaque);
        McpDispatcher withOpaque = new McpDispatcher(new McpToolRegistry(List.of(opaqueTool)), mapper);
        JsonNode params = mapper.readTree("{\"name\":\"opaque\",\"arguments\":{}}");

        Map<String, Object> result = resultMap(withOpaque.dispatch(req(1, "tools/call", params), ctx()));

        assertEquals(Boolean.FALSE, result.get("isError"));
        assertEquals(List.of(Map.of("type", "text", "text", "opaque result")), result.get("content"));
        assertEquals(opaque, result.get("structuredContent"));
    }

    /** A {@code tools/call} whose params are not an object, or name no tool, is the caller's error (-32602). */
    @ParameterizedTest
    @ValueSource(strings = {"\"echo\"", "{\"arguments\":{}}"})
    void toolsCall_paramsThatNameNoToolAreInvalidParams(String paramsJson) throws Exception {
        assertEquals(
                JsonRpc.INVALID_PARAMS,
                error(dispatcher.dispatch(req(1, "tools/call", mapper.readTree(paramsJson)), ctx()))
                        .code());
    }

    /** Arguments left out entirely are the same request as an empty object, not a -32602. */
    @Test
    void toolsCall_argumentsAbsent_callsHandlerWithEmptyMap() throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"echo\"}");

        Map<String, Object> result = resultMap(dispatcher.dispatch(req(1, "tools/call", params), ctx()));

        assertEquals(Map.of("echoed", Map.of()), result.get("structuredContent"));
    }

    /**
     * A batch element that decodes to nothing ({@code [null]}) reaches the dispatcher as a null request. It is an
     * invalid request answered with a null id, not a NullPointerException that would fail the whole batch.
     */
    @Test
    void aNullRequestIsAnInvalidRequestWithANullId() {
        JsonRpc.Response r = require(dispatcher.dispatch(null, ctx()));

        assertEquals(NullNode.getInstance(), r.id());
        assertEquals(JsonRpc.INVALID_REQUEST, error(r).code());
    }

    /**
     * A known method sent as a notification (no id at all, or a null id) is run but answered with nothing: a
     * response to a notification is a protocol violation clients may choke on.
     */
    @Test
    void aKnownMethodSentAsANotificationGetsNoResponse() {
        assertNull(dispatcher.dispatch(new JsonRpc.Request("2.0", null, "ping", null), ctx()));
        assertNull(dispatcher.dispatch(new JsonRpc.Request("2.0", NullNode.getInstance(), "ping", null), ctx()));
    }

    /** A client that names no protocol version is offered the revision this server implements. */
    @Test
    void initialize_withoutAClientVersionOffersTheServersOwn() {
        Map<String, Object> result = resultMap(dispatcher.dispatch(req(1, "initialize", null), ctx()));

        assertEquals("2025-06-18", result.get("protocolVersion"));
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.auth.TenantContext;
import ai.tessary.tenant.KeyScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The {@code POST /mcp} transport envelope, below the dispatcher: which requests reach a tool at all, what a
 * body that is not one JSON-RPC request becomes, and that every failure still answers with a JSON-RPC body a
 * client can parse. No Spring context: a real {@link McpDispatcher} over a two-tool registry, and the
 * {@link TenantContext} set on the request the way {@code AuthFilter} sets it. The authenticated end-to-end
 * path through {@code AuthFilter} is {@code McpControllerTest}'s.
 */
class McpControllerEnvelopeTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    /** A bean Jackson refuses to serialise (no properties), standing in for a tool result that cannot render. */
    private static final Object OPAQUE = new Object() {
        @Override
        public String toString() {
            return "opaque";
        }
    };

    private final McpController controller = new McpController(
            new McpDispatcher(
                    new McpToolRegistry(List.of(
                            new McpTool("echo", "echoes", Map.of(), (c, a) -> Map.of("echoed", a)),
                            new McpTool("opaque", "unrenderable", Map.of(), (c, a) -> OPAQUE))),
                    mapper),
            mapper);

    private static MockHttpServletRequest request(@Nullable TenantContext ctx) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.setAttribute(TenantContext.ATTRIBUTE, ctx);
        return req;
    }

    private static TenantContext key(KeyScope scope) {
        return new TenantContext("user-1", null, "org-1", "proj-1", "member", "tok-1", scope);
    }

    private JsonNode body(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(Objects.requireNonNull(r.getBody()));
    }

    /**
     * With auth disabled (dev) there is no context and so no project to bind: the endpoint is absent (404)
     * rather than running tools with nothing to scope them to. A context with no user is the same case.
     */
    @Test
    @SuppressWarnings("NullAway") // deliberate: a context with no user id is the unauthenticated shape
    void withNoAuthenticatedContextTheEndpointIsAbsent() {
        ResponseEntity<String> none = controller.handle(request(null), PING);
        ResponseEntity<String> anonymous =
                controller.handle(request(new TenantContext(null, null, null, "proj-1", null, "tok-1")), PING);

        assertEquals(HttpStatus.NOT_FOUND, none.getStatusCode());
        assertNull(none.getBody());
        assertEquals(HttpStatus.NOT_FOUND, anonymous.getStatusCode());
    }

    /**
     * Least privilege: a write- or query-scoped key is refused with a JSON-RPC error before any method runs,
     * so a leaked ingest key cannot read a project's traces through the tools.
     */
    @ParameterizedTest
    @EnumSource(
            value = KeyScope.class,
            names = {"WRITE", "QUERY"})
    void aKeyNotScopedForMcpIsRefusedBeforeAnyMethodRuns(KeyScope scope) throws Exception {
        ResponseEntity<String> r = controller.handle(request(key(scope)), PING);

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals(JsonRpc.INVALID_REQUEST, body(r).path("error").path("code").asInt());
        assertEquals(
                "this API key is not scoped for MCP tool access",
                body(r).path("error").path("message").asText());
        assertEquals(false, body(r).has("result"), "the ping must not have run");
    }

    /** No body at all, or only whitespace, is a 400 invalid request rather than a parse error or a 500. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankBodyIsAnInvalidRequest(String raw) throws Exception {
        ResponseEntity<String> r = controller.handle(request(key(KeyScope.ADMIN)), raw);

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals(JsonRpc.INVALID_REQUEST, body(r).path("error").path("code").asInt());
    }

    @Test
    @SuppressWarnings("NullAway") // deliberate: Spring passes null for a request with no body at all
    void anAbsentBodyIsAnInvalidRequest() throws Exception {
        ResponseEntity<String> r = controller.handle(request(key(KeyScope.ADMIN)), null);

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals(JsonRpc.INVALID_REQUEST, body(r).path("error").path("code").asInt());
    }

    /** A body that is not JSON is a 400 carrying JSON-RPC's parse-error code, with a null id to echo. */
    @Test
    void aBodyThatIsNotJsonIsAParseError() throws Exception {
        ResponseEntity<String> r = controller.handle(request(key(KeyScope.ADMIN)), "{not json");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals(JsonRpc.PARSE_ERROR, body(r).path("error").path("code").asInt());
        assertEquals(true, body(r).get("id").isNull());
    }

    /**
     * A batch answers every request in order and nothing for a notification, and one malformed element is a
     * parse error for that element alone (echoing its id) rather than failing the elements beside it.
     */
    @Test
    void aBatchAnswersEachRequestAndOnlyTheMalformedElementFails() throws Exception {
        String batch = "[" + PING
                + ",{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"
                + ",{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":[\"ping\"]}]";

        ResponseEntity<String> r = controller.handle(request(key(KeyScope.ADMIN)), batch);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        JsonNode out = body(r);
        assertEquals(2, out.size(), "the notification gets no response entry");
        assertEquals(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"), out.get(0));
        assertEquals(7, out.get(1).get("id").asInt());
        assertEquals(JsonRpc.PARSE_ERROR, out.get(1).path("error").path("code").asInt());
    }

    /** A batch of notifications only has nothing to answer: 204, not an empty array. */
    @Test
    void aBatchOfOnlyNotificationsIsNoContent() {
        ResponseEntity<String> r = controller.handle(
                request(key(KeyScope.ADMIN)), "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]");

        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode());
        assertNull(r.getBody());
    }

    /** A malformed single envelope with no id is a parse error answered with a null id. */
    @Test
    void aMalformedEnvelopeWithNoIdIsAParseErrorWithANullId() throws Exception {
        ResponseEntity<String> r =
                controller.handle(request(key(KeyScope.ADMIN)), "{\"jsonrpc\":\"2.0\",\"method\":{}}");

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(true, body(r).get("id").isNull());
        assertEquals(JsonRpc.PARSE_ERROR, body(r).path("error").path("code").asInt());
    }

    /**
     * A response that cannot be serialised is still a JSON-RPC body: the fixed internal-error envelope, never
     * an empty 200 or a Spring error page the client cannot parse.
     */
    @Test
    void aResponseThatCannotBeSerialisedIsTheFixedInternalErrorEnvelope() {
        ResponseEntity<String> r = controller.handle(
                request(key(KeyScope.ADMIN)),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"opaque\"}}");

        assertEquals(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"serialisation failed\"}}",
                r.getBody());
    }

    /**
     * Anything escaping the dispatcher becomes a 500 with a JSON-RPC internal-error body, so a client still
     * gets an envelope it can match and report rather than an HTML error page.
     */
    @Test
    void anExceptionEscapingTheDispatcherIsA500JsonRpcInternalError() throws Exception {
        McpDispatcher failing = new McpDispatcher(new McpToolRegistry(List.of()), mapper) {
            @Override
            public JsonRpc.@Nullable Response dispatch(JsonRpc.@Nullable Request req, TenantContext ctx) {
                throw new IllegalStateException("dispatcher bug");
            }
        };

        ResponseEntity<String> r = new McpController(failing, mapper).handle(request(key(KeyScope.ADMIN)), PING);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals(JsonRpc.INTERNAL_ERROR, body(r).path("error").path("code").asInt());
        assertEquals("dispatcher bug", body(r).path("error").path("message").asText());
    }
}

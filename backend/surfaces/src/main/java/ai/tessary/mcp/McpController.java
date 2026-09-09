// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosts the MCP Streamable HTTP transport at {@code POST /mcp}.
 *
 * <p>Auth is handled upstream by {@code AuthFilter}: it verifies the Bearer
 * token against {@code api_key}, populates {@link TenantContext} with the
 * bound project, and rejects unauthorised requests before we get here. This
 * controller just dispatches JSON-RPC envelopes; every tool reads
 * {@code ctx.projectId()} to scope its work.</p>
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpDispatcher dispatcher;
    private final ObjectMapper mapper;

    public McpController(McpDispatcher dispatcher, ObjectMapper mapper) {
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @PostMapping(
            value = "/mcp",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(HttpServletRequest req, @RequestBody(required = false) String rawBody) {
        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || !ctx.isAuthenticated()) {
            // AuthFilter normally short-circuits earlier; this is the fallback for
            // configurations that disable auth (dev). In disabled mode there's no
            // project context, so /mcp has nothing to bind — return 404.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // Least-privilege key family: only an mcp key may drive tool access; a write- or
        // query-scoped key presented here is rejected before any tool runs.
        if (!ctx.keyPermits(ai.tessary.tenant.KeyScope.ADMIN)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(jsonOrEmpty(JsonRpc.Response.err(
                            NullNode.getInstance(),
                            JsonRpc.INVALID_REQUEST,
                            "this API key is not scoped for MCP tool access",
                            null)));
        }
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(jsonOrEmpty(
                            JsonRpc.Response.err(NullNode.getInstance(), JsonRpc.INVALID_REQUEST, "empty body", null)));
        }
        JsonNode body;
        try {
            body = mapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(jsonOrEmpty(
                            JsonRpc.Response.err(NullNode.getInstance(), JsonRpc.PARSE_ERROR, e.getMessage(), null)));
        }

        try {
            if (body.isArray()) {
                List<JsonRpc.Response> out = new ArrayList<>();
                for (JsonNode el : body) {
                    var resp = dispatchOne(el, ctx);
                    if (resp != null) out.add(resp);
                }
                if (out.isEmpty()) return ResponseEntity.noContent().build();
                return ResponseEntity.ok(jsonOrEmpty(out));
            } else {
                var resp = dispatchOne(body, ctx);
                if (resp == null) return ResponseEntity.noContent().build();
                return ResponseEntity.ok(jsonOrEmpty(resp));
            }
        } catch (Exception e) {
            log.error("/mcp top-level dispatch failure", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonOrEmpty(JsonRpc.Response.err(
                            NullNode.getInstance(), JsonRpc.INTERNAL_ERROR, e.getMessage(), null)));
        }
    }

    private String jsonOrEmpty(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("failed to serialise MCP response", e);
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"serialisation failed\"}}";
        }
    }

    private JsonRpc.@Nullable Response dispatchOne(JsonNode envelope, TenantContext ctx) {
        JsonRpc.Request req;
        try {
            req = mapper.convertValue(envelope, new TypeReference<>() {});
        } catch (Exception e) {
            JsonNode id = envelope.get("id");
            return JsonRpc.Response.err(
                    id != null ? id : NullNode.getInstance(),
                    JsonRpc.PARSE_ERROR,
                    "malformed JSON-RPC envelope: " + e.getMessage(),
                    null);
        }
        return dispatcher.dispatch(req, ctx);
    }
}

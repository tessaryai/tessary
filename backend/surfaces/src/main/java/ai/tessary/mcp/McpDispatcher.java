// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC 2.0 for MCP: {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}. Tool errors go in the
 * {@code result} as {@code isError: true} per the spec; notifications are dropped; unknown methods get {@code -32601}.
 */
@Component
public class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

    /** A recognised client version is echoed back, else ours. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolRegistry registry;
    private final ObjectMapper mapper;

    public McpDispatcher(McpToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /** Null for a notification. */
    public JsonRpc.@Nullable Response dispatch(JsonRpc.@Nullable Request req, TenantContext ctx) {
        if (req == null || !"2.0".equals(req.jsonrpc())) {
            return JsonRpc.Response.err(idOrNull(req), JsonRpc.INVALID_REQUEST, "jsonrpc must be \"2.0\"", null);
        }
        JsonNode reqId = req.id();
        boolean isNotification = reqId == null || reqId.isNull();
        String reqMethod = req.method();
        String method = reqMethod == null ? "" : reqMethod;
        try {
            return switch (method) {
                case "initialize" -> ok(req, initialize(req.params()));
                case "ping" -> ok(req, Map.of());
                case "tools/list" -> ok(req, listTools());
                case "tools/call" -> ok(req, callTool(req.params(), ctx));
                default -> {
                    if (isNotification) yield null;
                    yield JsonRpc.Response.err(
                            idOrNull(req), JsonRpc.METHOD_NOT_FOUND, "method not found: " + method, null);
                }
            };
        } catch (IllegalArgumentException e) {
            return JsonRpc.Response.err(idOrNull(req), JsonRpc.INVALID_PARAMS, e.getMessage(), null);
        } catch (McpTool.ToolException e) {
            return ok(req, toolErrorResult(Objects.requireNonNull(e.getMessage())));
        } catch (Exception e) {
            log.error("internal error handling method {}", req.method(), e);
            return JsonRpc.Response.err(
                    idOrNull(req), JsonRpc.INTERNAL_ERROR, "internal error: " + e.getMessage(), null);
        }
    }

    private JsonRpc.@Nullable Response ok(JsonRpc.Request req, Object result) {
        JsonNode id = req.id();
        if (id == null || id.isNull()) return null;
        return JsonRpc.Response.ok(id, result);
    }

    private JsonNode idOrNull(JsonRpc.@Nullable Request req) {
        JsonNode id = req == null ? null : req.id();
        return id == null ? NullNode.getInstance() : id;
    }

    private Map<String, Object> initialize(@Nullable JsonNode params) {
        String clientVersion = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText()
                : PROTOCOL_VERSION;

        Map<String, Object> capabilities = Map.of("tools", Map.of("listChanged", false));
        Map<String, Object> serverInfo = Map.of(
                "name", "tessary-mcp",
                "version", "0.1.0");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", clientVersion);
        out.put("capabilities", capabilities);
        out.put("serverInfo", serverInfo);
        out.put("instructions", instructions());
        return out;
    }

    /**
     * Built from the tools the registry actually holds, since agents treat the instructions as authoritative. The
     * read-only sentence is unconditional: no tool writes.
     */
    private String instructions() {
        Set<String> available = new LinkedHashSet<>();
        for (McpTool t : registry.tools()) available.add(t.name());
        StringBuilder sb = new StringBuilder("MCP surface for the tessary backend. "
                + "Every tool is read-only: nothing you call can write, spend, or start an agent run. ");
        if (available.contains("get_project")) {
            sb.append("Start with get_project to see which project this token is bound to — every tool is "
                    + "scoped to it and none takes a project argument. Its watching block (enabled "
                    + "classifiers, call sites swept, traces_last_day) is what makes an empty case list "
                    + "readable: traces_last_day = 0 means nothing is ARRIVING, not that nothing is wrong. ");
        }
        if (available.contains("list_cases")) {
            sb.append("Use list_cases to answer \"what is wrong with this project right now\": open cases "
                    + "worst-first by default, paged, and narrowable by detector or call_site_id. get_case "
                    + "reads one case — its finding, and the RCA report inline once one has finished. ");
        }
        if (available.contains("list_call_sites")) {
            sb.append("Use list_call_sites / list_failure_modes to read the " + "imported pipeline taxonomy. ");
        }
        if (available.contains("list_findings")) {
            sb.append("Use list_findings / get_finding for classifier drift findings — the aggregated cause "
                    + "behind many firings, distinct from a single firing in the classifier_events dataset. "
                    + "get_finding_evidence pages the rows a finding actually measured: call it with "
                    + "count_only=true first for the per-role sizes, then page the ids and open them with "
                    + "get_trace / get_span. It never samples for you — if you take a subset, say so and say "
                    + "what you took. ");
        }
        if (available.contains("query_count")) {
            sb.append("Use the aggregation-first query tools query_count / query_timeseries / query_facets / "
                    + "query_search to ask aggregate questions of the trace + signal store, all scoped to "
                    + "this token's project. Call describe_dataset first for each dataset's facetable and "
                    + "filterable fields — they differ per dataset and an unknown field is an error, so read "
                    + "them rather than guessing. ");
        }
        if (available.contains("list_spans")) {
            sb.append("Use list_traces / list_spans / list_sessions to FIND something when you do not "
                    + "already have an id — each pages (limit default 50, max 100; pass the returned "
                    + "next_cursor back as cursor). list_spans searches spans; query_search does not "
                    + "take the spans dataset. ");
        }
        if (available.contains("get_span")) {
            sb.append("Then use get_span(trace_id, span_id) / get_trace(trace_id) / get_session(id) to READ "
                    + "one: lists are for finding and gets are for reading, so only these return the FULL raw "
                    + "payload (input/output/attributes) rather than previews. get_span needs BOTH ids: a span "
                    + "id is unique only within its trace. get_trace also returns the trace's rollup row "
                    + "(token buckets, costs, is_settled), so never add its spans up yourself, and it caps at "
                    + "200 spans with spans_truncated saying when it did. ");
        }
        return sb.toString().trim();
    }

    private Map<String, Object> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpTool t : registry.tools()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("description", t.description());
            entry.put("inputSchema", t.inputSchema());
            tools.add(entry);
        }
        return Map.of("tools", tools);
    }

    private Map<String, Object> callTool(@Nullable JsonNode params, TenantContext ctx) {
        if (params == null || !params.isObject() || !params.hasNonNull("name")) {
            throw new IllegalArgumentException("tools/call requires params.name");
        }
        String name = params.get("name").asText();
        McpTool tool = registry.tool(name);
        if (tool == null) {
            throw new McpTool.ToolException("unknown tool: " + name);
        }

        Map<String, Object> arguments;
        JsonNode argNode = params.get("arguments");
        if (argNode == null || argNode.isNull()) {
            arguments = Map.of();
        } else if (argNode.isObject()) {
            arguments = mapper.convertValue(argNode, new TypeReference<>() {});
        } else {
            throw new IllegalArgumentException("tools/call.arguments must be an object");
        }

        log.debug("mcp tools/call tool={} project={}", name, ctx.projectId());
        Object result = tool.handler().apply(ctx, arguments);
        return toolSuccessResult(result);
    }

    private Map<String, Object> toolSuccessResult(Object payload) {
        // MCP requires `content`; a single text block carries the JSON, plus `structuredContent` for typed clients.
        String text;
        try {
            text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            text = String.valueOf(payload);
        }
        Map<String, Object> textBlock = Map.of("type", "text", "text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textBlock));
        result.put("structuredContent", payload);
        result.put("isError", false);
        return result;
    }

    private Map<String, Object> toolErrorResult(String message) {
        Map<String, Object> textBlock = Map.of("type", "text", "text", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textBlock));
        result.put("isError", true);
        return result;
    }
}

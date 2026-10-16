// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sandbox;

import ai.tessary.ingest.export.TraceSpanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** All methods are best-effort: telemetry must never fail the surrounding sandbox call. */
public final class AgentSpanTelemetry {

    // A static bare mapper, not the JacksonConfig bean: this is a non-Spring utility, and the @Primary bean has
    // identical strict semantics.
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentSpanTelemetry() {}

    public static final String OP_CHAT = "chat"; // a single LLM call (one turn)
    public static final String OP_INVOKE_AGENT = "invoke_agent"; // a (multi-turn) agent invocation

    /** A failed run's span, marked failed for OTel and for Langfuse, which reads its own level attributes. */
    public static void markError(Span span, String message) {
        span.setStatus(StatusCode.ERROR, message);
        span.setAttribute("langfuse.observation.level", "ERROR");
        span.setAttribute("langfuse.observation.status_message", message);
    }

    /**
     * Stamped via {@code langfuse.observation.input}/{@code output} so the root is not mis-rendered as a model call.
     */
    public static void recordSpanIo(Span span, String inputText, String outputText) {
        try {
            if (inputText != null && !inputText.isBlank()) {
                span.setAttribute("langfuse.observation.input", inputText);
            }
            if (outputText != null && !outputText.isBlank()) {
                span.setAttribute("langfuse.observation.output", outputText);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static String messagesJson(String role, String content) {
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode msg = arr.addObject();
        msg.put("role", role);
        ObjectNode part = msg.putArray("parts").addObject();
        part.put("type", "text");
        part.put("content", content);
        return arr.toString();
    }

    /**
     * Results ride on the same turn so each span is a complete request, response and result triple, which agent-eval
     * graders score. Content is never truncated.
     */
    private static @Nullable String assistantTurnJson(String text, JsonNode toolCalls, JsonNode toolResults) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasCalls = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
        boolean hasResults = toolResults != null && toolResults.isArray() && !toolResults.isEmpty();
        if (!hasText && !hasCalls && !hasResults) return null;
        ArrayNode arr = JSON.createArrayNode();
        if (hasText || hasCalls) {
            ObjectNode msg = arr.addObject();
            msg.put("role", "assistant");
            ArrayNode parts = msg.putArray("parts");
            if (hasText) {
                ObjectNode p = parts.addObject();
                p.put("type", "text");
                p.put("content", text);
            }
            if (hasCalls) {
                for (JsonNode tc : toolCalls) {
                    ObjectNode p = parts.addObject();
                    p.put("type", "tool_call");
                    String id = tc.path("id").asText("");
                    if (!id.isBlank()) p.put("id", id);
                    p.put("name", tc.path("name").asText(""));
                    p.put("arguments", tc.path("input").asText(""));
                }
            }
        }
        if (hasResults) {
            ObjectNode msg = arr.addObject();
            msg.put("role", "tool");
            ArrayNode parts = msg.putArray("parts");
            for (JsonNode tr : toolResults) {
                ObjectNode p = parts.addObject();
                p.put("type", "tool_call_response");
                String id = tr.path("id").asText("");
                if (!id.isBlank()) p.put("id", id);
                p.put("response", tr.path("content").asText(""));
            }
        }
        return arr.toString();
    }

    /**
     * Null when the envelope carried no cost, and also when it says $0 for a run that used tokens: that is a missing
     * rate, not a free run.
     */
    public record AgentUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            @Nullable BigDecimal costUsd) {}

    public static @Nullable AgentUsage parseUsage(ObjectMapper mapper, @Nullable String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank()) return null;
        try {
            JsonNode u = mapper.readTree(envelopeJson).path("usage");
            long in = u.path("input_tokens").asLong(0);
            long out = u.path("output_tokens").asLong(0);
            long cacheRead = u.path("cache_read_input_tokens").asLong(0);
            long cacheWrite = u.path("cache_creation_input_tokens").asLong(0);
            BigDecimal cost = u.path("cost_usd").isNumber()
                    ? new BigDecimal(u.path("cost_usd").asText())
                    : null;
            boolean usedTokens = in + out + cacheRead + cacheWrite > 0;
            if (cost != null && cost.signum() == 0 && usedTokens) cost = null;
            return new AgentUsage(in, out, cacheRead, cacheWrite, cost);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return null;
        }
    }

    public static void recordUsage(Span span, ObjectMapper mapper, @Nullable String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank()) return;
        try {
            JsonNode env = mapper.readTree(envelopeJson);
            AgentUsage priced = parseUsage(mapper, envelopeJson);
            BigDecimal costUsd = priced == null ? null : priced.costUsd();
            if (costUsd != null) {
                double cost = costUsd.doubleValue();
                span.setAttribute("gen_ai.usage.cost", cost);
                ObjectNode costDetails = mapper.createObjectNode();
                costDetails.put("total", cost);
                span.setAttribute("langfuse.observation.cost_details", costDetails.toString());
            }
            JsonNode u = env.path("usage");
            long in = u.path("input_tokens").asLong(0);
            long out = u.path("output_tokens").asLong(0);
            long cacheRead = u.path("cache_read_input_tokens").asLong(0);
            long cacheCreate = u.path("cache_creation_input_tokens").asLong(0);
            span.setAttribute("gen_ai.usage.input_tokens", in);
            span.setAttribute("gen_ai.usage.output_tokens", out);
            ObjectNode usageDetails = mapper.createObjectNode();
            usageDetails.put("input", in);
            usageDetails.put("output", out);
            usageDetails.put("cache_read", cacheRead);
            usageDetails.put("cache_creation", cacheCreate);
            span.setAttribute("langfuse.observation.usage_details", usageDetails.toString());
            long turns = env.path("num_turns").asLong(0);
            if (turns > 0) span.setAttribute("gen_ai.usage.num_turns", turns);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
        }
    }

    /**
     * One assistant response can arrive as several stream-json events, and only the first carries a non-blank
     * {@code input}, so a later blank-input entry is a continuation folded into the turn in progress.
     */
    private static List<JsonNode> coalesceTurns(JsonNode turns) {
        List<JsonNode> out = new ArrayList<>();
        ObjectNode current = null;
        for (JsonNode t : turns) {
            if (!t.isObject()) continue;
            if (current != null && t.path("input").asText("").isBlank()) {
                mergeTurn(current, t);
            } else {
                current = (ObjectNode) t.deepCopy();
                out.add(current);
            }
        }
        return out;
    }

    private static void mergeTurn(ObjectNode cur, JsonNode t) {
        String tText = t.path("text").asText("");
        if (!tText.isBlank()) {
            String curText = cur.path("text").asText("");
            cur.put("text", curText.isBlank() ? tText : curText + "\n" + tText);
        }
        appendArray(cur, "tool_calls", t.path("tool_calls"));
        appendArray(cur, "tool_results", t.path("tool_results"));
        appendArray(cur, "tools", t.path("tools"));
        JsonNode tu = t.path("usage");
        if (tu.isObject()) {
            ObjectNode cu = cur.path("usage").isObject() ? (ObjectNode) cur.get("usage") : cur.putObject("usage");
            for (String k : List.of(
                    "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
                if (cu.has(k) || tu.has(k))
                    cu.put(k, cu.path(k).asLong(0) + tu.path(k).asLong(0));
            }
        }
        if (cur.path("model").asText("").isBlank()
                && !t.path("model").asText("").isBlank()) {
            cur.put("model", t.path("model").asText(""));
        }
        if (t.has("ts")) cur.put("ts", t.path("ts").asLong(0L)); // last fragment's ts ends the merged span
    }

    private static void appendArray(ObjectNode cur, String field, JsonNode src) {
        if (!src.isArray() || src.isEmpty()) return;
        ArrayNode dst = cur.path(field).isArray() ? (ArrayNode) cur.get(field) : cur.putArray(field);
        for (JsonNode e : src) dst.add(e.deepCopy());
    }

    /**
     * Turns are coalesced first so one span is one complete turn. Placed from microVM offsets and kept monotonic, so
     * clock skew can't push a turn outside the parent window.
     */
    public static void recordTurns(Tracer tracer, @Nullable JsonNode turns, long runStartMs, Instant parentStart) {
        if (turns == null || !turns.isArray() || turns.isEmpty()) return;
        try {
            long prevOffset = 0L;
            for (JsonNode turn : coalesceTurns(turns)) {
                long ts = turn.path("ts").asLong(0L);
                long offset = (runStartMs > 0 && ts >= runStartMs) ? ts - runStartMs : prevOffset;
                if (offset < prevOffset) offset = prevOffset; // keep spans monotonic + non-negative duration
                Span turnSpan = tracer.spanBuilder("agent.llm_request")
                        .setSpanKind(SpanKind.CLIENT)
                        .setStartTimestamp(parentStart.plusMillis(prevOffset))
                        .startSpan();
                try {
                    String turnModel = turn.path("model").asText("");
                    turnSpan.setAttribute("gen_ai.request.model", turnModel);
                    // Set even on a tool-only turn so it classifies.
                    turnSpan.setAttribute("gen_ai.operation.name", OP_CHAT);
                    if (!turnModel.isBlank()) {
                        turnSpan.setAttribute("gen_ai.provider.name", TraceSpanMapper.inferSystem(turnModel));
                    }
                    JsonNode u = turn.path("usage");
                    long in = u.path("input_tokens").asLong(0);
                    long out = u.path("output_tokens").asLong(0);
                    long cacheRead = u.path("cache_read_input_tokens").asLong(0);
                    long cacheCreate = u.path("cache_creation_input_tokens").asLong(0);
                    turnSpan.setAttribute("gen_ai.usage.input_tokens", in);
                    turnSpan.setAttribute("gen_ai.usage.output_tokens", out);
                    ObjectNode usageDetails = JSON.createObjectNode();
                    usageDetails.put("input", in);
                    usageDetails.put("output", out);
                    usageDetails.put("cache_read", cacheRead);
                    usageDetails.put("cache_creation", cacheCreate);
                    turnSpan.setAttribute("langfuse.observation.usage_details", usageDetails.toString());
                    JsonNode tools = turn.path("tools");
                    if (tools.isArray() && !tools.isEmpty()) {
                        turnSpan.setAttribute("tessary.agent.tools", tools.toString());
                    }
                    // Older payloads omit input and output.
                    String input = turn.path("input").asText("");
                    if (!input.isBlank()) {
                        turnSpan.setAttribute("gen_ai.input.messages", messagesJson("user", input));
                    }
                    String outMsg = assistantTurnJson(
                            turn.path("text").asText(""), turn.path("tool_calls"), turn.path("tool_results"));
                    if (outMsg != null) {
                        turnSpan.setAttribute("gen_ai.output.messages", outMsg);
                    }
                } finally {
                    turnSpan.end(parentStart.plusMillis(offset));
                }
                prevOffset = offset;
            }
        } catch (RuntimeException ignored) {
        }
    }
}

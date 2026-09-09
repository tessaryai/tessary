// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sandbox;

import ai.tessary.ingest.export.TraceSpanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared OTel/Langfuse enrichment for the E2B agent sandboxes that drive the coding agent in a
 * microVM (judge-prompt synthesis and deterministic codegen). Each gets back the agent's
 * stream-json telemetry (a result envelope + per-turn usage) from the launcher and stamps it
 * onto spans the same way, so the logic lives here once — in {@code sandbox} because more than one
 * feature now shares it.
 *
 * <p>All methods are best-effort: telemetry must never fail the surrounding sandbox call.
 */
public final class AgentSpanTelemetry {

    // Deliberately a static bare mapper, not the shared JacksonConfig bean: this is a
    // non-Spring static utility, and the @Primary bean has identical strict semantics.
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentSpanTelemetry() {}

    // OTel GenAI gen_ai.operation.name values (semconv v1.37.0; open enum). We carry the TRUE kind so the
    // platform can segregate agent vs single-LLM vs tool by the standard, not by a vendor's lossy type.
    public static final String OP_CHAT = "chat"; // a single LLM call (one turn)
    public static final String OP_INVOKE_AGENT = "invoke_agent"; // a (multi-turn) agent invocation
    public static final String OP_INVOKE_WORKFLOW = "invoke_workflow"; // a coordinator fanning out agents

    /**
     * Stamp the prompt + completion onto {@code span} as a Langfuse <em>generation</em>'s input/output —
     * the same {@code gen_ai.input.messages} / {@code gen_ai.output.messages} shape {@link
     * ai.tessary.llm.LlmCaller} uses, so the trace shows WHAT was asked and produced instead of a
     * content-less, token-only generation. {@code operationName} is the OTel {@code gen_ai.operation.name}
     * KIND ({@link #OP_CHAT}/{@link #OP_INVOKE_AGENT}/…) — the truthful discriminator the ingestion keys on.
     * Best-effort; content is stamped in FULL, never truncated — telemetry must be a faithful record for
     * debugging (we rely on the OTLP pipeline to carry large attributes rather than clipping here).
     */
    public static void recordIo(Span span, String operationName, String model, String inputText, String outputText) {
        try {
            span.setAttribute(
                    "gen_ai.operation.name",
                    operationName == null || operationName.isBlank() ? OP_CHAT : operationName);
            if (model != null && !model.isBlank()) {
                span.setAttribute("gen_ai.request.model", model);
                // semconv v1.37.0 renamed gen_ai.system -> gen_ai.provider.name.
                span.setAttribute("gen_ai.provider.name", TraceSpanMapper.inferSystem(model));
            }
            if (inputText != null && !inputText.isBlank()) {
                span.setAttribute("gen_ai.input.messages", messagesJson("user", inputText));
            }
            if (outputText != null && !outputText.isBlank()) {
                span.setAttribute("gen_ai.output.messages", messagesJson("assistant", outputText));
            }
        } catch (RuntimeException ignored) {
            // telemetry is best-effort; never fail the sandbox call over it
        }
    }

    /**
     * Stamp plain input/output strings onto a non-generation {@code span} (e.g. the orchestration root that
     * fans work out), via {@code langfuse.observation.input}/{@code output}, so the trace root shows its
     * context + result without being mis-rendered as a model call. Best-effort.
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
            // telemetry is best-effort
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
     * One assistant turn as an ordered OTel GenAI message list: an assistant message (a {@code text} part when
     * the model wrote prose, plus one {@code tool_call} part per tool it requested — {id, name, arguments}),
     * followed — when the results are known — by a {@code tool}-role message of {@code tool_call_response} parts
     * carrying those results. Bundling the results onto the SAME turn makes each span a complete
     * request→response→result triple (what agent-eval graders score) rather than a half-open pair whose result
     * leaked onto the next span. Mirrors the real Anthropic exchange; leaves carry FULL content (Jackson escapes
     * them, so the JSON stays valid) — never truncated. Returns null when the turn has neither text, tool calls,
     * nor results.
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
     * One sandbox run's token buckets and cost, as the runner's result envelope reports them — the
     * shape both the span enrichment below and the usage ledger read.
     *
     * <p>{@code costUsd} is what the agent itself reported for the run ({@code total_cost_usd}), not a
     * catalog price: the platform never assembles these requests, so it cannot re-derive them. Null
     * when the envelope carried no cost — the same unpriced sentinel the rest of the accounting uses.
     */
    public record AgentUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            @Nullable BigDecimal costUsd) {}

    /**
     * Read one result envelope's usage, or null when there is none to read (blank
     * envelope, unparseable JSON). Best-effort like everything else here — a caller books what it gets
     * and skips what it doesn't.
     */
    public static @Nullable AgentUsage parseUsage(ObjectMapper mapper, @Nullable String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank()) return null;
        try {
            JsonNode env = mapper.readTree(envelopeJson);
            JsonNode u = env.path("usage");
            return new AgentUsage(
                    u.path("input_tokens").asLong(0),
                    u.path("output_tokens").asLong(0),
                    u.path("cache_read_input_tokens").asLong(0),
                    u.path("cache_creation_input_tokens").asLong(0),
                    env.hasNonNull("total_cost_usd")
                            ? BigDecimal.valueOf(env.get("total_cost_usd").asDouble())
                            : null);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return null;
        }
    }

    /**
     * Stamp cost + token usage from the runner's result envelope onto {@code span} so Langfuse prices it
     * like any other generation (instead of a hollow model-only span). Mirrors the observer's
     * {@code E2bAnalysisSandbox.recordUsage}.
     */
    public static void recordUsage(Span span, ObjectMapper mapper, String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isBlank()) return;
        try {
            JsonNode env = mapper.readTree(envelopeJson);
            if (env.has("total_cost_usd")) {
                double cost = env.get("total_cost_usd").asDouble();
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
            // usage is best-effort telemetry; never fail the sandbox call over it
        }
    }

    /**
     * Coalesce the launcher's per-stream-json-event entries into one entry per LOGICAL turn. A single
     * assistant response can arrive as several {@code assistant} events (thinking / text / each tool_use),
     * and only the FIRST entry of a turn carries a non-blank {@code input} (the prompt for turn 0, the prior
     * tool results otherwise) — a real user/tool-result event is the only thing that populates it. So an entry
     * with a blank {@code input} (and not the first) is a CONTINUATION of the turn in progress: fold its text,
     * tool calls, tool results, tools and usage into that turn (and extend it to the continuation's {@code ts}).
     * The result is one entry per user→assistant turn, eliminating single-part fragment spans and content-less
     * thinking-only spans. A no-op once the in-VM capture already emits one entry per logical turn.
     */
    private static List<JsonNode> coalesceTurns(JsonNode turns) {
        List<JsonNode> out = new ArrayList<>();
        ObjectNode current = null;
        for (JsonNode t : turns) {
            if (!t.isObject()) continue;
            // continuation = a later stream-json event of the SAME assistant response (blank input)
            if (current != null && t.path("input").asText("").isBlank()) {
                mergeTurn(current, t);
            } else {
                current = (ObjectNode) t.deepCopy();
                out.add(current);
            }
        }
        return out;
    }

    /** Fold a continuation entry {@code t} into the in-progress turn {@code cur} (see {@link #coalesceTurns}). */
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

    /** Append every element of {@code src} (when a non-empty array) onto {@code cur}'s {@code field} array. */
    private static void appendArray(ObjectNode cur, String field, JsonNode src) {
        if (!src.isArray() || src.isEmpty()) return;
        ArrayNode dst = cur.path(field).isArray() ? (ArrayNode) cur.get(field) : cur.putArray(field);
        for (JsonNode e : src) dst.add(e.deepCopy());
    }

    /**
     * Render each LOGICAL assistant turn the launcher captured (stream-json) as a child
     * {@code agent.llm_request} span carrying that turn's model + token usage — so Langfuse shows the
     * agent's conversation rather than one opaque generation. The launcher emits one entry per stream-json
     * {@code assistant} event, and a harness (notably on Bedrock) can split a single assistant response into
     * SEPARATE events — a thinking block, a text block, then each {@code tool_use} — so a turn arrives
     * fragmented and only the first fragment carries the input. {@link #coalesceTurns} folds those fragments
     * back together first, so one span = one complete user→assistant turn (input + all text + all tool calls +
     * their results), not single-part fragments and empty thinking-only spans. Created with the current
     * context as parent, so each turn nests under it. Placed relative to {@code parentStart} using the microVM
     * offsets ({@code ts - runStartMs}), kept monotonic, so microVM↔host clock skew can't push a turn outside
     * the parent window.
     */
    public static void recordTurns(Tracer tracer, JsonNode turns, long runStartMs, Instant parentStart) {
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
                    // One coalesced user→assistant exchange → gen_ai.operation.name=chat (the truthful KIND;
                    // the parent author span is invoke_agent). Set even on a tool-only turn so it classifies.
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
                    // This turn's INPUT (what prompted this LLM call: the initial prompt on turn 0, the prior
                    // tool results on later turns) and OUTPUT (the assistant's text + structured tool_call parts,
                    // followed by the tool_call_response parts those calls produced — a complete triple on ONE
                    // span). All captured in-VM. Older payloads omit them.
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
            // turn spans are best-effort telemetry; never fail the sandbox call over them
        }
    }
}

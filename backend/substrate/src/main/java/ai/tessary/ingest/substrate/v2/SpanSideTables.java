// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.storage.Jsonb;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.ToolCallRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Structural extraction of the two side tables that outlive the v1 substrate: a {@code tool_call} per
 * dispatchable-tool span ({@code tool} or {@code mcp}), and the {@code retrieved_doc} rows a retrieval
 * span flattened into its attributes.
 *
 * <p><b>Both tool kinds, because they are the same event.</b> {@link KindNormalizer} promotes an
 * {@code execute_tool} span carrying {@code gen_ai.tool.type = extension} to kind {@code mcp} — a
 * statement about who HOSTS the tool, not about whether one ran. Gating on {@code tool} alone therefore
 * projected 60,571 of 93,368 dispatches and dropped every MCP call, so the tool-error surface watched 8
 * built-in tools and none of the 93 MCP ones, which fail more often. It also left
 * {@code tool_call.tool_type} NULL on every row in the table: {@code gen_ai.tool.type} is stated by
 * exactly the spans the gate excluded, while the ingestion contract promises the column is populated
 * from it. The gate stops here — {@code retrieval}/{@code embedding}/{@code reranker} have their own
 * side table and their own name semantics, and widening to them would be a contract change rather than
 * this contract repair.
 *
 * <p><b>Structural means no inference</b>, exactly as it did in the enricher this is lifted from: every
 * value here is read off an attribute the producer stated or off the OTLP span status. Nothing is
 * classified, nothing is guessed, and a span that states none of it produces no rows.
 *
 * <p><b>What changed in the move</b> is the keys and nothing else. These rows used to hang off a
 * platform-minted {@code observation_id}; they now carry the producer's own {@code (trace_id, span_id)}
 * in {@code trace_id}/{@code span_id} — the pair every read joins on since the read path moved —
 * and their primary keys are derived from that pair ({@link SideTableIds}) so redelivery still dedupes.
 * There is no window in which both writers exist: the enricher is deleted in the same change that adds
 * this.
 *
 * <p>{@code message}/{@code message_block} extraction is deliberately NOT carried over. Those tables are
 * removed by the teardown: their only live read was the traces-list preview, which the {@code trace}
 * preview columns serve now, and the detail payload they fed was never rendered (the chat view parses
 * span payloads directly).
 */
@Component
class SpanSideTables {

    private final ObjectMapper mapper;

    /**
     * Non-blank content handed to {@link Jsonb#orNull} that was not JSON, so the typed column took null.
     *
     * <p><b>The fail-open is correct; the silence was not.</b> {@code Jsonb.orNull} degrades rather than
     * throwing because a throw under autocommit takes the whole batch down, and Langfuse makes the same
     * call for the same reason — raw retained, typed view degraded. What it could not do was tell anyone
     * apart: a tool that printed prose instead of JSON and a document this platform corrupted itself both
     * arrived here as a quiet null. In one corpus that hid 10,852 tool calls, 48.9% of every MCP call,
     * whose arguments the redactor had rewritten into invalid JSON by substituting a bare token into a
     * numeric position. Nothing logged, nothing counted, and the column simply read empty.
     */
    private final AtomicLong unparseableToolArgs = new AtomicLong();

    private final AtomicLong unparseableToolResults = new AtomicLong();

    SpanSideTables(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Tool-call arguments that were present but not JSON. Steady-state this tracks producer noise only. */
    long unparseableToolArgs() {
        return unparseableToolArgs.get();
    }

    /** Tool-call results that were present but not JSON. */
    long unparseableToolResults() {
        return unparseableToolResults.get();
    }

    /**
     * {@link Jsonb#orNull}, counting the degradations. Same behaviour, same fail-open contract — the only
     * change is that a null now leaves a trace behind.
     */
    private static @Nullable String countingJsonb(@Nullable String raw, AtomicLong degradations) {
        String parsed = Jsonb.orNull(raw);
        if (parsed == null && raw != null && !raw.isBlank()) degradations.incrementAndGet();
        return parsed;
    }

    /** What one span contributed. Both halves are empty for the overwhelming majority of spans. */
    record Extracted(@Nullable ToolCallRow toolCall, List<RetrievedDocRow> retrievedDocs) {

        static final Extracted NONE = new Extracted(null, List.of());
    }

    /**
     * Extract whatever side-table rows this span states.
     *
     * @param input the already-externalized span input (media refs, never raw base64) — the fallback for
     *     tool arguments when the structured message carrier does not hold them.
     * @param output likewise for the tool result.
     */
    Extracted extract(
            String projectId,
            String traceId,
            String spanId,
            String kind,
            RawEntry raw,
            @Nullable String input,
            @Nullable String output,
            @Nullable Long latencyMs,
            String eventTs,
            String now) {
        ToolCallRow toolCall = KindNormalizer.TOOL.equals(kind) || KindNormalizer.MCP.equals(kind)
                ? toolCall(projectId, traceId, spanId, raw, input, output, latencyMs, eventTs, now)
                : null;
        List<RetrievedDocRow> docs = retrievedDocs(projectId, traceId, spanId, raw, eventTs, now);
        if (toolCall == null && docs.isEmpty()) return Extracted.NONE;
        return new Extracted(toolCall, docs);
    }

    // ----- tool calls ------------------------------------------------------------------------------

    /**
     * The first-class {@code tool_call} for a tool-kind span: name, args (the tool_call part's
     * {@code arguments}, else the whole span input), result (the tool_result part's {@code content}, else
     * the whole output), and a structural error read — the {@linkplain #toolError error} when
     * {@code level=ERROR}, both derived from the OTLP span status at the mapper edge.
     */
    private ToolCallRow toolCall(
            String projectId,
            String traceId,
            String spanId,
            RawEntry raw,
            @Nullable String input,
            @Nullable String output,
            @Nullable Long latencyMs,
            String eventTs,
            String now) {
        ToolError error = toolError(raw);
        // Prefer the standard gen_ai tool attributes over the span name, so the row is the tool itself
        // rather than the wrapping span.
        String name = firstNonBlank(str(raw.metadata(), GenAiAttributes.TOOL_NAME), raw.name());
        String args = firstNonBlank(toolPartField(raw.inputMessagesJson(), "tool_call", "arguments"), input);
        String result = firstNonBlank(
                countingJsonb(
                        toolPartField(raw.outputMessagesJson(), "tool_result", "content"), unparseableToolResults),
                countingJsonb(output, unparseableToolResults));
        return ToolCallRow.forSpan(
                SideTableIds.toolCall(projectId, traceId, spanId),
                projectId,
                traceId,
                spanId,
                name,
                str(raw.metadata(), GenAiAttributes.TOOL_CALL_ID),
                str(raw.metadata(), GenAiAttributes.TOOL_TYPE),
                countingJsonb(args, unparseableToolArgs),
                args,
                result,
                error == null ? null : error.type(),
                error == null ? null : error.message(),
                latencyMs,
                eventTs,
                raw.timestamp(),
                now);
    }

    /**
     * Structural tool-error read, when the source's {@code level} is {@code ERROR}: the class it names in
     * {@code error.type}, and the prose of its {@code statusMessage}. No interpretation of outputs.
     *
     * <p>The two used to be one column. {@code error_type} carried the status message verbatim, which is
     * how the column the tool-error classifier groups by came to hold multi-kilobyte prose (#762). See
     * {@link SpanErrors} for how the class is derived.
     *
     * <p>The type is never null here, unlike on the span: {@code is_error} is written from it, and a
     * {@code level=ERROR} tool call that describes itself no further is still a failure the classifier
     * must count.
     *
     * @return null when the span reports no error at all.
     */
    private static @Nullable ToolError toolError(RawEntry raw) {
        String level = str(raw.metadata(), "level");
        if (level == null || !"ERROR".equalsIgnoreCase(level)) return null;
        String message = str(raw.metadata(), GenAiAttributes.STATUS_MESSAGE);
        String type = SpanErrors.errorClass(str(raw.metadata(), GenAiAttributes.ERROR_TYPE), message);
        return new ToolError(type != null ? type : "tool call reported level=ERROR", SpanErrors.cappedMessage(message));
    }

    /** A tool call's failure, split into the class that groups it and the prose that describes it. */
    private record ToolError(String type, @Nullable String message) {}

    /**
     * Pull one field from the first part of {@code partType} in a gen_ai messages JSON array
     * ({@code [{role, parts:[{type,…}]}]}) — a tool_call's {@code arguments} or a tool_result's
     * {@code content}. Returns the value as a string (JSON text for objects/arrays), or null.
     */
    private @Nullable String toolPartField(@Nullable String messagesJson, String partType, String field) {
        if (messagesJson == null || messagesJson.isBlank()) return null;
        try {
            JsonNode msgs = mapper.readTree(messagesJson);
            if (!msgs.isArray()) return null;
            for (JsonNode m : msgs) {
                JsonNode parts = m.get("parts");
                if (parts == null || !parts.isArray()) continue;
                for (JsonNode p : parts) {
                    if (partType.equals(p.path("type").asText(""))) {
                        JsonNode v = p.get(field);
                        if (v == null || v.isNull()) return null;
                        return v.isValueNode() ? v.asText() : v.toString();
                    }
                }
            }
        } catch (JsonProcessingException e) {
            return null; // auxiliary — never fail a span over it
        }
        return null;
    }

    // ----- retrieved documents ---------------------------------------------------------------------

    /**
     * Re-assemble a retrieval span's passages from the OpenInference indexed-document attributes
     * ({@code retrieval.documents.{N}.document.{id|content|score|metadata}}) the span flattened into its
     * attribute bag. No-op for the common non-retriever case, which pays only a key scan.
     */
    private List<RetrievedDocRow> retrievedDocs(
            String projectId, String traceId, String spanId, RawEntry raw, String eventTs, String now) {
        Map<String, Object> meta = raw.metadata();
        if (meta == null || meta.isEmpty()) return List.of();
        Map<Integer, String> ids = new HashMap<>();
        Map<Integer, String> contents = new HashMap<>();
        Map<Integer, Double> scores = new HashMap<>();
        Map<Integer, String> metas = new HashMap<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null || !k.startsWith(GenAiAttributes.OI_RETRIEVAL_DOCUMENTS_PREFIX)) continue;
            if (k.endsWith(GenAiAttributes.OI_DOC_CONTENT_SUFFIX)) {
                putByIndex(contents, k, GenAiAttributes.OI_DOC_CONTENT_SUFFIX, String.valueOf(v));
            } else if (k.endsWith(GenAiAttributes.OI_DOC_ID_SUFFIX)) {
                putByIndex(ids, k, GenAiAttributes.OI_DOC_ID_SUFFIX, String.valueOf(v));
            } else if (k.endsWith(GenAiAttributes.OI_DOC_SCORE_SUFFIX)) {
                Double s = toDouble(v);
                if (s != null) putByIndex(scores, k, GenAiAttributes.OI_DOC_SCORE_SUFFIX, s);
            } else if (k.endsWith(GenAiAttributes.OI_DOC_METADATA_SUFFIX)) {
                putByIndex(metas, k, GenAiAttributes.OI_DOC_METADATA_SUFFIX, String.valueOf(v));
            }
        }
        if (contents.isEmpty() && ids.isEmpty()) return List.of();
        SortedSet<Integer> indices = new TreeSet<>();
        indices.addAll(contents.keySet());
        indices.addAll(ids.keySet());
        indices.addAll(scores.keySet());
        indices.addAll(metas.keySet());
        List<RetrievedDocRow> out = new ArrayList<>(indices.size());
        for (Integer idx : indices) {
            // A retrieval step's documents are the retrieval `result` list; rank is 1-based.
            out.add(RetrievedDocRow.retrieved(
                    SideTableIds.retrievedDoc(projectId, traceId, spanId, idx),
                    projectId,
                    traceId,
                    spanId,
                    RetrievedDocRow.ListRole.RESULT,
                    idx,
                    ids.get(idx),
                    contents.get(idx),
                    scores.get(idx),
                    null, // source_uri — not in the current OpenInference doc attributes
                    null, // data_source_id
                    metas.get(idx),
                    eventTs,
                    now));
        }
        return out;
    }

    /** Parse the {@code N} between the prefix and {@code suffix}, and index {@code value} by it. */
    private static <T> void putByIndex(Map<Integer, T> target, String key, String suffix, T value) {
        String mid =
                key.substring(GenAiAttributes.OI_RETRIEVAL_DOCUMENTS_PREFIX.length(), key.length() - suffix.length());
        try {
            target.put(Integer.parseInt(mid), value);
        } catch (NumberFormatException ignored) {
            // a non-numeric index is not an OI indexed-document attribute — skip it
        }
    }

    private static @Nullable Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----- helpers ---------------------------------------------------------------------------------

    private static @Nullable String str(@Nullable Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

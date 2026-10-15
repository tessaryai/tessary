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
 * Both {@code tool} and {@code mcp} kinds, since {@code mcp} only says who hosts the tool; gating on {@code tool} alone
 * dropped every MCP call. Structural: values are read off stated attributes or span status, never inferred. Primary
 * keys derive from the producer's {@code (trace_id, span_id)} ({@link SideTableIds}) so redelivery dedupes.
 */
@Component
class SpanSideTables {

    private final ObjectMapper mapper;

    /**
     * {@code Jsonb.orNull} fails open because a throw under autocommit sinks the batch, but silent nulls once hid 48.9%
     * of MCP call arguments that the redactor had corrupted. So degradations are counted.
     */
    private final AtomicLong unparseableToolArgs = new AtomicLong();

    private final AtomicLong unparseableToolResults = new AtomicLong();

    SpanSideTables(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    long unparseableToolArgs() {
        return unparseableToolArgs.get();
    }

    long unparseableToolResults() {
        return unparseableToolResults.get();
    }

    private static @Nullable String countingJsonb(@Nullable String raw, AtomicLong degradations) {
        String parsed = Jsonb.orNull(raw);
        if (parsed == null && raw != null && !raw.isBlank()) degradations.incrementAndGet();
        return parsed;
    }

    record Extracted(@Nullable ToolCallRow toolCall, List<RetrievedDocRow> retrievedDocs) {

        static final Extracted NONE = new Extracted(null, List.of());
    }

    /** @param input already externalized (media refs, never raw base64) */
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
        // Prefer the gen_ai tool attributes, so the row is the tool rather than the wrapping span.
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
     * Splits {@code error.type} from the status prose, which once filled the grouping column with multi-kilobyte text.
     * The type is never null, since {@code is_error} is written from it.
     */
    private static @Nullable ToolError toolError(RawEntry raw) {
        String level = str(raw.metadata(), "level");
        if (level == null || !"ERROR".equalsIgnoreCase(level)) return null;
        String message = str(raw.metadata(), GenAiAttributes.STATUS_MESSAGE);
        String type = SpanErrors.errorClass(str(raw.metadata(), GenAiAttributes.ERROR_TYPE), message);
        return new ToolError(type != null ? type : "tool call reported level=ERROR", SpanErrors.cappedMessage(message));
    }

    private record ToolError(String type, @Nullable String message) {}

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

    /** No-op for the common non-retriever case, which pays only a key scan. */
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

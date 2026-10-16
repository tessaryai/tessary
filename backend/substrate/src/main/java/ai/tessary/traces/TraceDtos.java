// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.storage.TraceDetectionRepository;
import ai.tessary.storage.TraceV2Repository;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Trace list shapes and the one mapping onto them, shared by the traces list, session detail and MCP
 * {@code list_traces}: a second mapping is how a column starts meaning two things. Detail shapes stay on the
 * controller.
 */
public final class TraceDtos {

    private TraceDtos() {}

    /**
     * {@code status} is null for an unrolled trace, neither ok nor errored. Previews are the stored preview columns, a
     * truncation, not the payload.
     */
    public record TraceListItem(
            String id,
            @Nullable String name,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("ended_at") @Nullable String endedAt,
            @JsonProperty("latency_ms") @Nullable Long latencyMs,
            @Nullable String session,
            @JsonProperty("user_id") @Nullable String userId,
            @JsonProperty("thread_id") @Nullable String threadId,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            @JsonProperty("span_count") @Nullable Integer spanCount,
            @JsonProperty("error_count") @Nullable Integer errorCount,
            @Nullable String status,
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Long cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Long cacheWriteTokens,
            @JsonProperty("reasoning_tokens") @Nullable Long reasoningTokens,
            @JsonProperty("total_tokens") @Nullable Long totalTokens,
            @JsonProperty("input_cost") @Nullable BigDecimal inputCost,
            @JsonProperty("output_cost") @Nullable BigDecimal outputCost,
            @JsonProperty("total_cost") @Nullable BigDecimal totalCost,
            // A trace with unrated models shows its total and the unpriced part, so a small number is never mistaken
            // for a cheap turn.
            @JsonProperty("unpriced_spans") @Nullable Integer unpricedSpans,
            // "Nothing has arrived since the last rollup" — never "we gave up waiting".
            @JsonProperty("is_settled") boolean isSettled,
            @JsonProperty("input_preview") @Nullable String inputPreview,
            @JsonProperty("output_preview") @Nullable String outputPreview,
            // A flag cleared by a false-alarm resolve is not one.
            @JsonProperty("detected_by") List<DetectionLabel> detectedBy) {}

    public record DetectionLabel(
            @JsonProperty("classifier_id") String classifierId, String name) {}

    /**
     * One classifier's flag on a trace, at the span it judged, so a view can mark where it occurred. {@code grain}
     * {@code trace} judged the person's turn, {@code span} one span's output.
     */
    public record DetectionMark(
            @JsonProperty("classifier_id") String classifierId,
            String name,
            @JsonProperty("trace_id") String traceId,
            @JsonProperty("span_id") @Nullable String spanId,
            String grain) {}

    /** Null cursor at the end. */
    public record TracesPage(
            List<TraceListItem> traces,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /** The only such mapping. */
    public static List<TraceListItem> items(
            List<TraceV2Repository.Summary> rows, List<TraceDetectionRepository.Mark> marks) {
        Map<String, List<DetectionLabel>> labels = new HashMap<>();
        for (TraceDetectionRepository.Mark m : marks) {
            List<DetectionLabel> forTrace = labels.computeIfAbsent(m.traceId(), k -> new ArrayList<>());
            DetectionLabel label = new DetectionLabel(m.classifierId(), m.classifierName());
            if (!forTrace.contains(label)) {
                forTrace.add(label);
            }
        }
        return rows.stream()
                .map(s -> item(s, labels.getOrDefault(s.id(), List.of())))
                .toList();
    }

    public static List<DetectionMark> marks(List<TraceDetectionRepository.Mark> marks) {
        return marks.stream()
                .map(m -> new DetectionMark(
                        m.classifierId(),
                        m.classifierName(),
                        m.traceId(),
                        m.spanId(),
                        m.grain().subjectKind()))
                .toList();
    }

    private static TraceListItem item(TraceV2Repository.Summary s, List<DetectionLabel> detectedBy) {
        Integer errors = s.errorCount();
        return new TraceListItem(
                s.id(),
                s.name(),
                s.startedAt(),
                s.endedAt(),
                s.latencyMs(),
                s.sessionId(),
                s.userId(),
                s.threadId(),
                s.callSiteId(),
                s.spanCount(),
                errors,
                errors == null ? null : (errors > 0 ? "error" : "ok"),
                s.inputTokens(),
                s.outputTokens(),
                s.cacheReadTokens(),
                s.cacheWriteTokens(),
                s.reasoningTokens(),
                s.totalTokens(),
                s.inputCost(),
                s.outputCost(),
                s.totalCost(),
                s.unpricedSpans(),
                s.isSettled(),
                s.inputPreview(),
                s.outputPreview(),
                detectedBy);
    }
}

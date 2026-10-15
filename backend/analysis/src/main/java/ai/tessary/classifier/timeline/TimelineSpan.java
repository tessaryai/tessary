// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRow;
import ai.tessary.storage.TraceV2Repository;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One span as the timeline builder reads it, with the call site already resolved (the span's, else the trace's). @param
 * parentTraceId set when the trace is a detached sub-agent trace @param model the span's model id, else the producer's
 * model name @param tool null when the span has no {@code tool_call} row
 */
public record TimelineSpan(
        String traceId,
        @Nullable String parentTraceId,
        String spanId,
        @Nullable String parentSpanId,
        String kind,
        @Nullable String name,
        @Nullable String callSiteId,
        @Nullable String status,
        @Nullable String errorType,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable String model,
        Usage usage,
        @Nullable String input,
        @Nullable String output,
        @Nullable String attributes,
        @Nullable Tool tool) {

    /** Either may be null. */
    public static TimelineSpan of(
            TraceV2Repository.SessionTrace trace,
            SpanRow span,
            @Nullable SpanPayloadRow payload,
            @Nullable ToolCallRow toolCall) {
        String spanCallSite = span.callSiteId();
        String modelId = span.modelId();
        String endedAt = span.endedAt();
        return new TimelineSpan(
                span.traceId(),
                trace.parentTraceId(),
                span.id(),
                span.parentSpanId(),
                span.kind(),
                span.name(),
                spanCallSite == null ? trace.callSiteId() : spanCallSite,
                span.status(),
                span.errorType(),
                Instant.parse(span.startedAt()),
                endedAt == null ? null : Instant.parse(endedAt),
                modelId == null ? span.providedModelName() : modelId,
                new Usage(
                        span.inputTokens(),
                        span.outputTokens(),
                        span.cacheReadTokens(),
                        span.cacheWriteTokens(),
                        span.reasoningTokens(),
                        span.totalTokens()),
                payload == null ? null : payload.input(),
                payload == null ? null : payload.output(),
                payload == null ? null : payload.attributes(),
                toolCall == null ? null : new Tool(toolCall.name(), arguments(toolCall), toolCall.result()));
    }

    /** Else the text a producer sent, which ingest keeps only in {@code arguments_raw}. */
    private static @Nullable String arguments(ToolCallRow toolCall) {
        String parsed = toolCall.arguments();
        return parsed == null ? toolCall.argumentsRaw() : parsed;
    }

    public record Usage(
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable Long totalTokens) {

        public static final Usage NONE = new Usage(null, null, null, null, null, null);
    }

    /** {@code result} is null when it is not a JSON object or array; the span's output then holds it. */
    public record Tool(
            @Nullable String name,
            @Nullable String arguments,
            @Nullable String result) {}
}

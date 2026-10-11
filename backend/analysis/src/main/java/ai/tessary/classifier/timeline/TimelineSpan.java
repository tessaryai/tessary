// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One span as the timeline builder reads it: the span row's facts, its payload and its tool call, with the call site
 * already resolved (the span's, else the trace's).
 *
 * @param parentTraceId set when the trace is a detached sub-agent trace
 * @param kind the stored canonical kind
 * @param model the span's model id, else the model name the producer gave
 * @param tool the {@code tool_call} row of the span, or null when there is none
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

    public record Usage(
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable Long totalTokens) {

        public static final Usage NONE = new Usage(null, null, null, null, null, null);
    }

    /** The tool call's name ({@code gen_ai.tool.name}), arguments and result, as stored. */
    public record Tool(
            @Nullable String name,
            @Nullable String arguments,
            @Nullable String result) {}
}

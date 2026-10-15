// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code span_payload} row, 1:1 with {@link SpanRow}. Off-row because it is most of the bytes and least of the
 * reads, which is also why it ages out ahead of {@code span}. @param providedUsage the producer's raw usage, kept as a
 * receipt and never read for arithmetic @param redactions JSON array of {@code RedactionStamp}; null when none
 */
public record SpanPayloadRow(
        String projectId,
        String traceId,
        String spanId,
        @Nullable String input,
        @Nullable String output,
        @Nullable String attributes,
        @Nullable String providedUsage,
        String eventTs,
        @Nullable String redactions) {

    public SpanPayloadRow(
            String projectId,
            String traceId,
            String spanId,
            @Nullable String input,
            @Nullable String output,
            @Nullable String attributes,
            @Nullable String providedUsage,
            String eventTs) {
        this(projectId, traceId, spanId, input, output, attributes, providedUsage, eventTs, null);
    }
}

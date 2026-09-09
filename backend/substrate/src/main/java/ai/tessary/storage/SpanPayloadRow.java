// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code span_payload} row — the raw payload, 1:1 with {@link SpanRow} (substrate-model.md §4).
 *
 * <p>Off-row because it is most of the bytes and the least of the reads: it is read when a single span is
 * opened, and no list surface touches it. That is also why it ages out ahead of {@code span} — a span whose
 * payload has been purged stays fully functional on every list and rollup surface.
 *
 * @param providedUsage the producer's raw usage object, kept as a receipt and NEVER read for arithmetic.
 *     When an unmodelled token bucket starts mattering, it gets promoted to a real column on {@code span}
 *     and backfilled from here.
 */
public record SpanPayloadRow(
        String projectId,
        String traceId,
        String spanId,
        @Nullable String input,
        @Nullable String output,
        @Nullable String attributes,
        @Nullable String providedUsage,
        String eventTs) {}

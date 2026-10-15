// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Normalized shape every ingest path emits; {@code input}/{@code output} carry media inline. The OTLP-native trailing
 * fields ({@code endTimestamp}, {@code inputMessagesJson}, {@code outputMessagesJson}) are null on blob-only paths. A
 * Kafka spool record may still carry the retired {@code sourceUrl} and {@code callSiteId}; decoding ignores both.
 */
@JsonIgnoreProperties({"sourceUrl", "callSiteId"})
public record RawEntry(
        @Nullable String sourceExternalId,
        @Nullable String name,
        @Nullable String input,
        @Nullable String output,
        @Nullable String model,
        @Nullable Map<String, Object> metadata,
        @Nullable String parentId,
        @Nullable String traceId,
        /** ISO-8601 observation start time; nullable. Orders turns within a trace. */
        @Nullable String timestamp,
        /**
         * Normalized operation kind ({@link KindNormalizer}); nullable. This, not a vendor observation type, tells an
         * agent-outcome span from a single LLM call.
         */
        @Nullable String operationKind,
        /** ISO-8601 end time; nullable, only OTLP spans carry it. */
        @Nullable String endTimestamp,
        /** Raw {@code gen_ai.input.messages}; nullable. Never inferred from an unstructured blob. */
        @Nullable String inputMessagesJson,
        @Nullable String outputMessagesJson,
        /**
         * Written only by {@code RedactionService}, the last hop that rebuilds an entry before the write, so nothing
         * after it can drop this.
         */
        @Nullable List<RedactionStamp> redactions) {

    /**
     * The v2 substrate's version stamp: end time when sent, else start time, so last-write-wins resolves a completed
     * span over its partial. Derived rather than a component because {@code RedactionService} rebuilds entries
     * field-by-field and could drop a stored one.
     */
    public @Nullable String eventTs() {
        return endTimestamp != null ? endTimestamp : timestamp;
    }

    public RawEntry(
            @Nullable String sourceExternalId,
            @Nullable String name,
            @Nullable String input,
            @Nullable String output,
            @Nullable String model,
            @Nullable Map<String, Object> metadata,
            @Nullable String parentId,
            @Nullable String traceId,
            @Nullable String timestamp,
            @Nullable String operationKind) {
        this(
                sourceExternalId,
                name,
                input,
                output,
                model,
                metadata,
                parentId,
                traceId,
                timestamp,
                operationKind,
                null,
                null,
                null);
    }

    public RawEntry(
            @Nullable String sourceExternalId,
            @Nullable String name,
            @Nullable String input,
            @Nullable String output,
            @Nullable String model,
            @Nullable Map<String, Object> metadata,
            @Nullable String parentId,
            @Nullable String traceId,
            @Nullable String timestamp,
            @Nullable String operationKind,
            @Nullable String endTimestamp,
            @Nullable String inputMessagesJson,
            @Nullable String outputMessagesJson) {
        this(
                sourceExternalId,
                name,
                input,
                output,
                model,
                metadata,
                parentId,
                traceId,
                timestamp,
                operationKind,
                endTimestamp,
                inputMessagesJson,
                outputMessagesJson,
                null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Normalized shape every ingest path emits. Downstream code never sees provider-specific JSON.
 *
 * <p>Inline-media contract: {@code input}/{@code output} carry media INLINE — a {@code data:} URI, raw
 * base64 in an Anthropic/OpenAI content block, or an {@code https://} image URL. Out-of-band provider
 * media refs that still appear in some dialect payloads (e.g. legacy Langfuse media tokens, Braintrust
 * attachment refs) are resolved to inline form by {@code MediaResolver} BEFORE the RawEntry is built,
 * so the whole downstream (storage in TEXT columns, ContentExtractor, the judge, the frontend) stays
 * media-agnostic. v1 stores resolved bytes inline; large-binary externalization is the deferred
 * MediaStore SPI (see {@code MediaResolver}).
 *
 * <p><b>OTLP-native structural fields ({@code endTimestamp}, {@code inputMessagesJson},
 * {@code outputMessagesJson}).</b> Some push/JSONL paths carry none of these — a provider observation
 * gives a start time and stringified input/output blobs, nothing
 * more. The OTLP receiver reads them natively off the span (end-time, {@code gen_ai.input.messages}
 * / {@code gen_ai.output.messages}), so they are <em>optional, trailing, default-null</em> fields a source
 * populates only when it actually has the structure. They light up two seams the blob-only paths cannot
 * offer: a span's measured duration (from {@code endTimestamp} − {@code timestamp}), and the structured
 * tool-call/tool-result carriers {@code SpanSideTables} reads clean arguments and results out of. Paths
 * that only have stringified blobs keep using the shorter back-compat constructors below.
 */
public record RawEntry(
        @Nullable String sourceExternalId,
        @Nullable String sourceUrl,
        @Nullable String name,
        @Nullable String input,
        @Nullable String output,
        @Nullable String model,
        @Nullable Map<String, Object> metadata,
        @Nullable String parentId,
        @Nullable String traceId,
        /** ISO-8601 observation start time, when the provider supplies one. Nullable.
         *  Orders turns within a trace. The trace-grouping transform that consumed that
         *  ordering to pick the latest turn went with grading; the ordering
         *  itself is still what the substrate writes and every read surface sorts on. */
        @Nullable String timestamp,
        /** Normalized OTel operation kind ({@link KindNormalizer}: agent/llm/tool/retrieval/workflow), derived
         *  from {@code gen_ai.operation.name} at the adapter boundary. Nullable when the source carries no
         *  operation name. This — not a vendor observation "type" — is how the platform tells an agent-outcome
         *  span from a single LLM call, and is the field a future OTLP receiver populates natively. */
        @Nullable String operationKind,
        /** ISO-8601 observation END time. Nullable — only OTLP spans carry a span end-time today. When present
         *  together with {@link #timestamp}, a tool call's {@code latencyMs} is derived from it. */
        @Nullable String endTimestamp,
        /** Raw {@code gen_ai.input.messages} value (a JSON-encoded {@code [{role, content|parts}]} array), as the
         *  OTLP span carries it. Nullable; only the OTLP path supplies structured role-tagged messages. Never
         *  interpreted into roles from an unstructured blob — only used when the source states the
         *  structure. */
        @Nullable String inputMessagesJson,
        /** Raw {@code gen_ai.output.messages} value (JSON-encoded role-tagged array). Nullable; see
         *  {@link #inputMessagesJson}. */
        @Nullable String outputMessagesJson,
        /** Pre-resolved call site. The substrate adapter ({@code SubstrateSource}) sets this from
         *  the ingested observation's {@code call_site_id}, so an sdk-source run grades by the id the substrate
         *  already resolved at ingest rather than re-deriving it through per-source mappings. Null for every
         *  other adapter (Langfuse/Braintrust/Phoenix pull, JSONL upload, the OTLP receiver), which leave
         *  call-site resolution to {@code MappingResolver} at ingest time. */
        @Nullable String callSiteId) {

    /**
     * The version stamp the v2 substrate orders redeliveries by: the span's END time when the producer
     * sent one, else its start time (substrate-model.md §6.2). A completed version of a span always carries a later end than the partial that
     * preceded it, so last-write-wins resolves the two correctly; ties go to the latest arrival.
     *
     * <p><b>Derived, not a component, deliberately.</b> A hop on this path rebuilds a {@link RawEntry}
     * field-by-field — {@code RedactionService} — and it runs BEFORE the write. A stored extra component
     * would be silently dropped by that copy the day someone adds a field and forgets a constructor,
     * and the symptom would be every span versioning on a null stamp.
     * As a function of two fields those copies already carry, it cannot be lost.
     */
    public @Nullable String eventTs() {
        return endTimestamp != null ? endTimestamp : timestamp;
    }

    /** Back-compat constructor for adapters/tests that don't yet supply an operation kind ({@code null}). */
    public RawEntry(
            @Nullable String sourceExternalId,
            @Nullable String sourceUrl,
            @Nullable String name,
            @Nullable String input,
            @Nullable String output,
            @Nullable String model,
            @Nullable Map<String, Object> metadata,
            @Nullable String parentId,
            @Nullable String traceId,
            @Nullable String timestamp) {
        this(sourceExternalId, sourceUrl, name, input, output, model, metadata, parentId, traceId, timestamp, null);
    }

    /**
     * Back-compat constructor for adapters/tests that supply an {@code operationKind} but none of the
     * OTLP-native structural fields ({@code endTimestamp}/messages) — i.e. every pull adapter and the upload
     * parser. The three trailing OTLP fields default to {@code null}.
     */
    public RawEntry(
            @Nullable String sourceExternalId,
            @Nullable String sourceUrl,
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
                sourceUrl,
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
                null,
                null);
    }

    /**
     * Back-compat constructor for the pre-call-site arity — every pull adapter, the upload parser, and the
     * OTLP receiver. {@code callSiteId} defaults to {@code null}, so only {@code SubstrateSource} (which
     * reads an already-resolved call site off the substrate) populates it.
     */
    public RawEntry(
            @Nullable String sourceExternalId,
            @Nullable String sourceUrl,
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
                sourceUrl,
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

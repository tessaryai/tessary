// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.export;

import ai.tessary.ingest.RawEntry;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turns one v2 {@code (span, span_payload)} pair — the shape {@link TracesController}'s existing detail
 * read already fetches — into the {@link RawEntry} {@link TraceSpanMapper} consumes, for the trace export
 * endpoint. Mirrors {@code ingest/substrate/SubstrateSource#toRawEntry}'s field mapping exactly
 * (same producer-id-preserving identity: {@code sourceExternalId} is the composite
 * {@code "<trace_id>:<span_id>"} handle, {@code parentId} is the parent's handle in the same trace) —
 * that mapper reads {@code SpanRepository.SpanEntry} (a joined row), this one reads the two rows
 * {@link ai.tessary.traces.TracesController#detail} already holds separately, so no new query is
 * added for the export.
 *
 * <p>Public, not package-private, because its caller ({@code TracesController}) lives in the
 * {@code ai.tessary.traces} package, not this one.
 */
public final class SpanRowMapper {

    private SpanRowMapper() {}

    public static RawEntry toRawEntry(SpanRow s, @Nullable SpanPayloadRow payload, ObjectMapper mapper) {
        String parentSpanId = s.parentSpanId();
        return new RawEntry(
                s.traceId() + ':' + s.id(), // sourceExternalId — composite handle, mirrors SubstrateSource
                null, // sourceUrl — not stored in the substrate
                s.name(),
                payload == null ? null : payload.input(),
                payload == null ? null : payload.output(),
                s.providedModelName(),
                parseMetadata(payload == null ? null : payload.attributes(), mapper),
                // parentId — the parent's handle in the SAME trace, so the tree holds on re-import.
                parentSpanId == null ? null : s.traceId() + ':' + parentSpanId,
                s.traceId(),
                s.startedAt(),
                s.kind(), // operationKind — already normalized at ingest
                s.endedAt(),
                null, // inputMessagesJson — not re-derived from the payload string here
                null, // outputMessagesJson
                s.callSiteId());
    }

    private static Map<String, Object> parseMetadata(@Nullable String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // A malformed attributes blob must not fail the whole export — this span's line just carries
            // no gen_ai.usage.*/tool.* attrs, same first-do-no-harm posture as the rest of the ingest path.
            return Map.of();
        }
    }
}

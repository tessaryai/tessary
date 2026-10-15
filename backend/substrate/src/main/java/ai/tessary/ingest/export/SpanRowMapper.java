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
 * Turns one v2 {@code (span, span_payload)} pair, as {@link TracesController}'s detail read already fetches it, into
 * the {@link RawEntry} {@link TraceSpanMapper} consumes for trace export. {@code sourceExternalId} is
 * {@code "<trace_id>:<span_id>"}. Public because its caller lives in {@code ai.tessary.traces}.
 */
public final class SpanRowMapper {

    private SpanRowMapper() {}

    public static RawEntry toRawEntry(SpanRow s, @Nullable SpanPayloadRow payload, ObjectMapper mapper) {
        String parentSpanId = s.parentSpanId();
        return new RawEntry(
                s.traceId() + ':' + s.id(), // sourceExternalId — composite handle
                s.name(),
                payload == null ? null : payload.input(),
                payload == null ? null : payload.output(),
                s.providedModelName(),
                parseMetadata(payload == null ? null : payload.attributes(), mapper),
                // The parent's handle in the same trace, so the tree holds on re-import.
                parentSpanId == null ? null : s.traceId() + ':' + parentSpanId,
                s.traceId(),
                s.startedAt(),
                s.kind(), // operationKind — already normalized at ingest
                s.endedAt(),
                null, // inputMessagesJson — not re-derived from the payload string here
                null); // outputMessagesJson
    }

    private static Map<String, Object> parseMetadata(@Nullable String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // A malformed attributes blob must not fail the export; this line just carries no usage or tool attrs.
            return Map.of();
        }
    }
}

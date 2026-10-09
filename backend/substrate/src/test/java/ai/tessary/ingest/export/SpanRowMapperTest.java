// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.ingest.RawEntry;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpanRowMapperTest {

    /**
     * A stored span re-enters the export as a raw entry keyed by its trace-qualified handles, carrying its
     * attribute bag as metadata; a bag that is not a JSON object costs the line its usage attributes, never
     * the export.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "null",
            value = {"{\"gen_ai.usage.input_tokens\": 5} | 5", "{not json | null", "null | null", "[1, 2] | null"})
    void aStoredSpanBecomesARawEntryWithItsAttributeBag(String attributes, @Nullable Integer inputTokens) {
        SpanRow span = new SpanRow(
                "p",
                "t",
                "s",
                "parent",
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                "chat",
                false,
                null,
                null,
                null,
                null,
                "2026-08-12T10:00:00Z",
                "2026-08-12T10:00:01Z",
                null,
                null,
                "gpt-4o",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SpanRow.CostSource.UNPRICED,
                null,
                null,
                null,
                SpanRow.ResolverState.PENDING,
                SpanRow.ResolverState.PENDING,
                "2026-08-12T10:00:01Z",
                false,
                null,
                null,
                null,
                null);
        SpanPayloadRow payload =
                new SpanPayloadRow("p", "t", "s", "in", "out", attributes, null, "2026-08-12T10:00:01Z", null);

        RawEntry raw = SpanRowMapper.toRawEntry(span, payload, new ObjectMapper());

        assertEquals("t:s", raw.sourceExternalId());
        assertEquals("t:parent", raw.parentId(), "the parent handle stays inside the same trace");
        assertEquals("in", raw.input());
        assertEquals(inputTokens == null ? Map.of() : Map.of("gen_ai.usage.input_tokens", inputTokens), raw.metadata());
    }
}

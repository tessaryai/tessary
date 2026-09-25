// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.ToolCallRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What one span contributes to the tool-call and retrieved-document side tables, read off its attributes. */
class SpanSideTablesTest {

    private static final String AT = "2026-08-12T10:00:00Z";

    private final SpanSideTables sideTables = new SpanSideTables(new ObjectMapper());

    /**
     * A retrieval span's passages are reassembled by their index, whatever order the attributes arrive in; a
     * score is read whether it came as a number or as text, and an attribute whose index is not a number, or
     * whose score is not one, is skipped rather than failing the span.
     */
    @Test
    void retrievedDocumentsAreReassembledInIndexOrder() {
        Map<String, Object> attrs = Map.of(
                "retrieval.documents.1.document.content", "second",
                "retrieval.documents.1.document.score", 2,
                "retrieval.documents.1.document.metadata", "{\"page\": 4}",
                "retrieval.documents.0.document.id", "doc-0",
                "retrieval.documents.0.document.content", "first",
                "retrieval.documents.0.document.score", "0.75",
                "retrieval.documents.x.document.content", "not an indexed document",
                "retrieval.documents.2.document.score", "high");

        var extracted =
                sideTables.extract("p", "t", "s", KindNormalizer.RETRIEVAL, raw(attrs, null), null, null, null, AT, AT);

        assertNull(extracted.toolCall(), "a retrieval span is not a tool call");
        assertEquals(
                List.of(
                        RetrievedDocRow.retrieved(
                                SideTableIds.retrievedDoc("p", "t", "s", 0),
                                "p",
                                "t",
                                "s",
                                "result",
                                0,
                                "doc-0",
                                "first",
                                0.75,
                                null,
                                null,
                                null,
                                AT,
                                AT),
                        RetrievedDocRow.retrieved(
                                SideTableIds.retrievedDoc("p", "t", "s", 1),
                                "p",
                                "t",
                                "s",
                                "result",
                                1,
                                null,
                                "second",
                                2.0,
                                null,
                                null,
                                "{\"page\": 4}",
                                AT,
                                AT)),
                extracted.retrievedDocs());
    }

    /**
     * A tool call's arguments come from the tool_call part of its input messages when there is one; messages
     * that are not JSON, not an array, carry no parts, or carry no tool_call part leave the span's plain input
     * as the arguments rather than dropping them.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"arguments\":{\"q\":\"x\"}}]}] | {\"q\":\"x\"}",
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"arguments\":\"q=x\"}]}] | q=x",
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\"}]}] | plain input",
                "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"hi\"}]}] | plain input",
                "[{\"role\":\"user\"}] | plain input",
                "{\"role\":\"user\"} | plain input",
                "[{not json | plain input"
            })
    void toolArgumentsComeFromTheToolCallPartElseFromTheInput(String inputMessagesJson, String arguments) {
        var extracted = sideTables.extract(
                "p", "t", "s", KindNormalizer.TOOL, raw(Map.of(), inputMessagesJson), "plain input", null, 12L, AT, AT);

        ToolCallRow row = extracted.toolCall();
        assertNotNull(row);
        assertEquals(arguments, row.argumentsRaw());
    }

    private static RawEntry raw(Map<String, Object> attrs, @Nullable String inputMessagesJson) {
        return new RawEntry("s", "search", null, null, null, attrs, null, "t", AT, null, null, inputMessagesJson, null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentExtractor#partPlaceholder}: the three ContentBlock document kinds render the bare
 * {@code [file]} label, never {@code [unsupported]}; tool parts render the terse outcome marker the
 * ingest preview shows.
 */
class ContentExtractorPartPlaceholderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode node(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void documentRef_rendersBareFileLabel() throws Exception {
        assertEquals(
                "[file]",
                ContentExtractor.partPlaceholder(
                        node("{\"type\":\"document_ref\",\"data\":\"m1\",\"mediaType\":\"application/pdf\","
                                + "\"text\":\"extracted\"}")));
    }

    @Test
    void documentB64_rendersBareFileLabel() throws Exception {
        assertEquals(
                "[file]",
                ContentExtractor.partPlaceholder(
                        node("{\"type\":\"document_b64\",\"data\":\"cGRm\",\"mediaType\":\"application/pdf\"}")));
    }

    @Test
    void documentUrl_rendersBareFileLabel_notUnsupported() throws Exception {
        // The regression this test pins: document_url carries only "url" (no text/content field), so
        // omitting it from partPlaceholder's switch falls to the default branch and partTextField finds
        // nothing, silently degrading to "[unsupported]".
        assertEquals(
                "[file]",
                ContentExtractor.partPlaceholder(node("{\"type\":\"document_url\",\"url\":\"https://e/r.pdf\"}")));
    }

    @Test
    void toolError_collapsesUnicodeWhitespace() throws Exception {
        // The bug: an ASCII-only \s leaves the NBSPs in the snippet, so the same error renders with
        // different spacing depending on where it came from.
        assertEquals(
                "[tool error: timeout after 30s]",
                ContentExtractor.partPlaceholder(node("{\"type\":\"tool_result\",\"is_error\":true,"
                        + "\"content\":\"timeout\\u00a0\\u00a0after\\n 30s\"}")));
    }

    @Test
    void toolError_truncatesAt120CodePointsWithoutSplittingAnEmoji() throws Exception {
        // The bug: a UTF-16 substring at 120 chars cuts the emoji straddling the boundary in half and
        // leaves a lone surrogate in the preview.
        String head = "x".repeat(119) + "\uD83D\uDE00";
        String error = head + " and more after the cap";
        String part = MAPPER.writeValueAsString(Map.of("type", "tool_result", "is_error", true, "content", error));
        assertEquals("[tool error: " + head + "]", ContentExtractor.partPlaceholder(node(part)));
    }
}

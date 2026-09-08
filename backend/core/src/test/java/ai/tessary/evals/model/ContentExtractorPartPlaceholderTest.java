// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentExtractor#partPlaceholder} for the three ContentBlock document kinds (#985/#987) — the
 * cross-language contract with Python's {@code render_part}/{@code _FILE_TYPES}
 * ({@code classifiers/framework/context.py}). {@code context_contract.json} carries zero
 * document_ref/document_b64/document_url fixture cases, so this is the only place a Java/Python
 * placeholder divergence for these kinds gets caught.
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
        // nothing, silently degrading to "[unsupported]" — a byte-for-byte divergence from Python's
        // _FILE_TYPES, which includes "document_url" and renders "[file]" for the same input.
        assertEquals(
                "[file]",
                ContentExtractor.partPlaceholder(node("{\"type\":\"document_url\",\"url\":\"https://e/r.pdf\"}")));
    }
}

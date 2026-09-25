// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.RawEntry;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.open.media.MediaStore.StoredMedia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verifies the exporter produces OTel GenAI spans the evals plugin's Path A can
 * consume (see plugins/plugins/evals/examples/sample_traces.jsonl for the
 * reference shape).
 */
class TraceSpanMapperTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static RawEntry raw(String input, String output, String model) {
        return new RawEntry(
                "span-1", "chat", input, output, model, Map.of(), "parent-1", "trace-1", "2026-05-22T10:00:00Z", null);
    }

    private static JsonNode parseAttr(ObjectNode span, String attr) throws Exception {
        // gen_ai.*.messages attributes are JSON-encoded strings.
        return M.readTree(span.get("attributes").get(attr).asText());
    }

    @Test
    void openAiMessagesInput_preservesRoles() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"system\",\"content\":\"You are a planner\"},{\"role\":\"user\",\"content\":\"plan X\"}]",
                        "the plan",
                        "claude-sonnet-4-6"),
                "docs-qa",
                null,
                null);

        assertEquals("anthropic", span.get("attributes").get("gen_ai.system").asText());
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(2, in.size());
        assertEquals("system", in.get(0).get("role").asText());
        assertEquals(
                "You are a planner",
                in.get(0).get("parts").get(0).get("content").asText());
        assertEquals("user", in.get(1).get("role").asText());

        JsonNode out = parseAttr(span, "gen_ai.output.messages");
        assertEquals(1, out.size());
        assertEquals("assistant", out.get(0).get("role").asText());
        assertEquals("the plan", out.get(0).get("parts").get(0).get("content").asText());
        assertEquals("end_turn", out.get(0).get("finish_reason").asText());
    }

    @Test
    void plainTextInput_wrapsAsSingleUserMessage() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw("How do I rotate the db password?", "use the runbook", "gpt-4o-mini"), "support", null, null);

        assertEquals("openai", span.get("attributes").get("gen_ai.system").asText());
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(1, in.size());
        assertEquals("user", in.get(0).get("role").asText());
        assertEquals(
                "How do I rotate the db password?",
                in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void anthropicContentParts_flattenToText() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"text\",\"text\":\"there\"}]}]",
                        "hello",
                        "claude-3-5-haiku"),
                "svc",
                null,
                null);

        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals("user", in.get(0).get("role").asText());
        assertEquals("hi\nthere", in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void carriesIdentityAndRequiredKeys() {
        ObjectNode span = TraceSpanMapper.toSpan(raw("q", "a", "claude-sonnet-4-6"), "svc", null, null);

        assertEquals("SpanKind.CLIENT", span.get("kind").asText());
        assertEquals("trace-1", span.get("context").get("trace_id").asText());
        assertEquals("span-1", span.get("context").get("span_id").asText());
        assertEquals("parent-1", span.get("parent_id").asText());
        assertEquals("2026-05-22T10:00:00Z", span.get("start_time").asText());
        assertEquals("OK", span.get("status").get("status_code").asText());
        assertEquals(
                "svc",
                span.get("resource").get("attributes").get("service.name").asText());

        // Required gen_ai.* attributes the plugin reads.
        JsonNode attrs = span.get("attributes");
        for (String k : new String[] {
            "gen_ai.system",
            "gen_ai.operation.name",
            "gen_ai.request.model",
            "gen_ai.input.messages",
            "gen_ai.output.messages"
        }) {
            assertTrue(attrs.has(k), "missing attribute " + k);
        }
        assertTrue(span.has("events"));
    }

    @Test
    void toSpanLine_isSingleLineJson() {
        String line = TraceSpanMapper.toSpanLine(raw("q", "a", "gpt-4o"), "svc", null, null);
        assertFalse(line.contains("\n"), "a JSONL line must not contain newlines");
        assertTrue(line.startsWith("{") && line.endsWith("}"));
    }

    @Test
    void imageInput_realHttpUrl_neverFetched_emitsLabeledPlaceholderPart() throws Exception {
        // A real http(s) image_url is never fetched at export time (same no-fetch posture as
        // ingest); it becomes a labeled placeholder carrying the URL and flags has_media.
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://e/x.png\"}}]}]",
                        "a magenta pixel",
                        "gpt-4o"),
                "svc",
                null,
                null);

        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(1, in.size());
        assertTrue(in.get(0).get("has_media").asBoolean(), "the message must be flagged has_media");
        // The image must appear as a labeled placeholder, not dropped.
        StringBuilder all = new StringBuilder();
        for (JsonNode part : in.get(0).get("parts"))
            all.append(part.get("content").asText()).append("|");
        assertTrue(all.toString().contains("what is this"), "text part preserved");
        assertTrue(all.toString().contains("[image: https://e/x.png]"), "a real URL becomes a labeled placeholder");
    }

    @Test
    void anthropicBase64Image_inlinedAsDataUri_realPreservation() throws Exception {
        // A base64 image's bytes are already inline, so export writes them verbatim as a
        // data: URI in the content field instead of an "[image omitted]" label.
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\""
                                + b64 + "\"}}]}]",
                        "ok",
                        "claude-sonnet-4-6"),
                "svc",
                null,
                null);
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        String content = in.get(0).get("parts").get(0).get("content").asText();
        assertEquals("data:image/jpeg;base64," + b64, content, "the real bytes are inlined, not labeled away");
    }

    @Test
    void imageRef_mediaStoreHit_inlinesRealBytes() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        MediaStore store = fakeStore(Map.of("m1", new StoredMedia(new MediaRef("m1"), "image/png", png)));
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"image_ref\",\"data\":\"m1\",\"mediaType\":\"image/png\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                store,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        String content = in.get(0).get("parts").get(0).get("content").asText();
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), content);
    }

    @Test
    void imageRef_mediaStoreMiss_fallsBackToOmittedLabel_stillLossless() throws Exception {
        MediaStore empty = fakeStore(Map.of());
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"image_ref\",\"data\":\"gone\",\"mediaType\":\"image/png\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                empty,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[image omitted: image/png]",
                in.get(0).get("parts").get(0).get("content").asText(),
                "an unresolvable ref falls back to the honest label, never a silent drop");
    }

    @Test
    void documentB64_inlinedAsDataUri() throws Exception {
        // The real Anthropic wire shape TraceSpanMapper receives from a payload; see
        // anthropicBase64Image_inlinedAsDataUri_realPreservation for the image counterpart.
        String b64 =
                Base64.getEncoder().encodeToString("PDF bytes here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":"
                                + "{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"" + b64
                                + "\"}}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                null,
                null);
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "data:application/pdf;base64," + b64,
                in.get(0).get("parts").get(0).get("content").asText());
        assertTrue(in.get(0).get("has_media").asBoolean());
    }

    @Test
    void documentRef_mediaStoreHit_inlinesRealBytes() throws Exception {
        byte[] pdf = "real pdf bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MediaStore store = fakeStore(Map.of("d1", new StoredMedia(new MediaRef("d1"), "application/pdf", pdf)));
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document_ref\",\"data\":\"d1\","
                                + "\"mediaType\":\"application/pdf\",\"text\":\"extracted text (ignored for export)\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                store,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf),
                in.get(0).get("parts").get(0).get("content").asText(),
                "export preserves the real bytes, not the extracted text");
    }

    @Test
    void documentRef_mediaStoreMiss_fallsBackToOmittedLabel() throws Exception {
        MediaStore empty = fakeStore(Map.of());
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document_ref\",\"data\":\"gone\","
                                + "\"mediaType\":\"application/pdf\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                empty,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[document omitted: application/pdf]",
                in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void documentRef_mediaStoreMiss_fallsBackToExtractedText_whenPresent() throws Exception {
        MediaStore empty = fakeStore(Map.of());
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document_ref\",\"data\":\"gone\","
                                + "\"mediaType\":\"application/pdf\",\"text\":\"extracted at ingest\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                empty,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[document: application/pdf]\nextracted at ingest",
                in.get(0).get("parts").get(0).get("content").asText(),
                "a media-store miss must not discard text already extracted at ingest");
    }

    @Test
    void documentRef_mediaStoreMiss_fallsBackToOmittedLabel_whenTextIsUnavailableMarker() throws Exception {
        MediaStore empty = fakeStore(Map.of());
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document_ref\",\"data\":\"gone\","
                                + "\"mediaType\":\"application/pdf\",\"text\":\"[document text unavailable]\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                empty,
                "p1");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[document omitted: application/pdf]",
                in.get(0).get("parts").get(0).get("content").asText(),
                "the ingest-time failure marker is not real text and must not be shipped as if it were");
    }

    @Test
    void documentUrl_realHttpUrl_neverFetched_emitsLabeledPlaceholder() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":"
                                + "{\"type\":\"url\",\"url\":\"https://e/report.pdf\"}}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                null,
                null);
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[document: https://e/report.pdf]",
                in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void textOnlyMessage_unchanged_noHasMediaFlag() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw("[{\"role\":\"user\",\"content\":\"plain question\"}]", "answer", "gpt-4o"), "svc", null, null);
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "plain question", in.get(0).get("parts").get(0).get("content").asText());
        assertFalse(in.get(0).has("has_media"), "a text-only message carries no has_media flag");
    }

    /** In-memory MediaStore keyed by ref id. */
    private static MediaStore fakeStore(Map<String, StoredMedia> byId) {
        return new MediaStore() {
            @Override
            public MediaRef put(String projectId, byte[] bytes, String mediaType) {
                throw new UnsupportedOperationException("read-only stub");
            }

            @Override
            public Optional<StoredMedia> get(String projectId, MediaRef ref) {
                return Optional.ofNullable(byId.get(ref.id()));
            }
        };
    }

    @Test
    void inferSystem_mapsCommonModels() {
        assertEquals("anthropic", TraceSpanMapper.inferSystem("claude-sonnet-4-6"));
        assertEquals("openai", TraceSpanMapper.inferSystem("gpt-4o-mini"));
        assertEquals("openai", TraceSpanMapper.inferSystem("o3-mini"));
        assertEquals("google", TraceSpanMapper.inferSystem("gemini-2.0-flash"));
        assertEquals("other", TraceSpanMapper.inferSystem("llama-3.1-70b"));
        assertEquals("other", TraceSpanMapper.inferSystem(null));
    }

    /**
     * A payload that is not a role-tagged message array still exports as one message: a role-less part
     * list keeps its text, a message whose content is null (a tool-call-only turn) keeps its role with an
     * empty text part, and JSON that does not parse is carried verbatim rather than lost.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[{\"type\":\"text\",\"text\":\"hi\"}] | [{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"hi\"}]}]",
                "[{\"role\":\"tool\",\"content\":null}] | [{\"role\":\"tool\",\"parts\":[{\"type\":\"text\",\"content\":\"\"}]}]",
                "[{\"role\": | [{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"[{\\\"role\\\":\"}]}]"
            })
    void nonMessagePayloads_exportAsASingleMessage(String input, String expectedMessages) {
        ObjectNode span = TraceSpanMapper.toSpan(raw(input, "ok", "gpt-4o"), "svc", null, null);

        assertEquals(
                expectedMessages,
                span.get("attributes").get("gen_ai.input.messages").asText());
    }

    /**
     * A stored document whose media type came through blank still says what it is when its bytes are gone:
     * its extracted text is labelled as the PDF a document defaults to, never as an image or as nothing.
     */
    @Test
    void documentRef_withABlankMediaType_isLabelledAsAPdf() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document_ref\",\"data\":\"gone\","
                                + "\"mediaType\":\"\",\"text\":\"Revenue rose.\"}]}]",
                        "ok",
                        "gpt-4o"),
                "svc",
                fakeStore(Map.of()),
                "p1");

        assertEquals(
                "[document: application/pdf]\nRevenue rose.",
                parseAttr(span, "gen_ai.input.messages")
                        .get(0)
                        .get("parts")
                        .get(0)
                        .get("content")
                        .asText());
    }

    /** Token counts a producer sent as text are exported as numbers, skipping an alias that is not one. */
    @Test
    void usageCountsSentAsText_areExportedAsNumbers() {
        RawEntry entry = new RawEntry(
                "span-1",
                "chat",
                "q",
                "a",
                "gpt-4o",
                Map.of("prompt_tokens", " 12 ", "output_tokens", "n/a", "completion_tokens", "7"),
                null,
                "trace-1",
                "2026-05-22T10:00:00Z",
                null);

        JsonNode attrs = TraceSpanMapper.toSpan(entry, "svc", null, null).get("attributes");

        assertEquals(12L, attrs.get("gen_ai.usage.input_tokens").asLong());
        assertEquals(7L, attrs.get("gen_ai.usage.output_tokens").asLong());
    }
}

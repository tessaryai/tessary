// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.open.media.MediaStore;
import ai.tessary.evals.open.media.MediaStore.MediaRef;
import ai.tessary.evals.open.media.MediaStore.StoredMedia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies the exporter produces OTel GenAI spans the evals plugin's Path A can
 * consume (see plugins/plugins/evals/examples/sample_traces.jsonl for the
 * reference shape).
 */
class TraceSpanMapperTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static RawEntry raw(String input, String output, String model) {
        return new RawEntry(
                "span-1",
                "https://src/span-1",
                "chat",
                input,
                output,
                model,
                Map.of(),
                "parent-1",
                "trace-1",
                "2026-05-22T10:00:00Z");
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
                "docs-qa");

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
                raw("How do I rotate the db password?", "use the runbook", "gpt-4o-mini"), "support");

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
                "svc");

        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals("user", in.get(0).get("role").asText());
        assertEquals("hi\nthere", in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void carriesIdentityAndRequiredKeys() {
        ObjectNode span = TraceSpanMapper.toSpan(raw("q", "a", "claude-sonnet-4-6"), "svc");

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
        String line = TraceSpanMapper.toSpanLine(raw("q", "a", "gpt-4o"), "svc");
        assertFalse(line.contains("\n"), "a JSONL line must not contain newlines");
        assertTrue(line.startsWith("{") && line.endsWith("}"));
    }

    @Test
    void imageInput_realHttpUrl_neverFetched_emitsLabeledPlaceholderPart() throws Exception {
        // An OpenAI image_url part with a REAL http(s) URL must NOT vanish in the text flatten, and
        // must NOT be fetched at export time (same no-fetch posture as ingest/the judge boundary) — it
        // stays a labeled placeholder carrying the URL, and flags the message has_media.
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://e/x.png\"}}]}]",
                        "a magenta pixel",
                        "gpt-4o"),
                "svc");

        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(1, in.size());
        assertTrue(in.get(0).get("has_media").asBoolean(), "the message must be flagged has_media");
        // Concatenate the parts' content; the image must appear as a labeled placeholder, not dropped.
        StringBuilder all = new StringBuilder();
        for (JsonNode part : in.get(0).get("parts"))
            all.append(part.get("content").asText()).append("|");
        assertTrue(all.toString().contains("what is this"), "text part preserved");
        assertTrue(all.toString().contains("[image: https://e/x.png]"), "a real URL becomes a labeled placeholder");
    }

    @Test
    void anthropicBase64Image_inlinedAsDataUri_realPreservation() throws Exception {
        // #986: a base64 image's bytes are already inline (no MediaStore round trip needed) — the
        // export now inlines them verbatim as a data: URI in the plain-string content field, rather
        // than always collapsing to the pre-#986 "[image omitted: <mediaType>]" label.
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\""
                                + b64 + "\"}}]}]",
                        "ok",
                        "claude-sonnet-4-6"),
                "svc");
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
        // The real Anthropic wire shape (as TraceSpanMapper actually receives it from a payload) — see
        // anthropicBase64Image_inlinedAsDataUri_realPreservation's image counterpart.
        String b64 =
                Base64.getEncoder().encodeToString("PDF bytes here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode span = TraceSpanMapper.toSpan(
                raw(
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":"
                                + "{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"" + b64
                                + "\"}}]}]",
                        "ok",
                        "gpt-4o"),
                "svc");
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
                "export preserves the real bytes, not the extracted text — #986 is byte preservation");
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
                "a media-store miss must not discard text already extracted at ingest (#986/#987)");
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
                "svc");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "[document: https://e/report.pdf]",
                in.get(0).get("parts").get(0).get("content").asText());
    }

    @Test
    void textOnlyMessage_unchanged_noHasMediaFlag() throws Exception {
        ObjectNode span = TraceSpanMapper.toSpan(
                raw("[{\"role\":\"user\",\"content\":\"plain question\"}]", "answer", "gpt-4o"), "svc");
        JsonNode in = parseAttr(span, "gen_ai.input.messages");
        assertEquals(
                "plain question", in.get(0).get("parts").get(0).get("content").asText());
        assertFalse(in.get(0).has("has_media"), "a text-only message carries no has_media flag");
    }

    /** In-memory MediaStore keyed by ref id, mirroring ContentBlocksMediaRefTest's fake. */
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
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.model.ContentExtractor.RoleMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link ContentExtractor}'s text and block views of a stored gen_ai payload: the per-message thread view
 * ({@link ContentExtractor#columnMessages}), the single-role text view ({@link ContentExtractor#columnText})
 * and the typed block view ({@link ContentExtractor#blocksFromContent}). {@code partPlaceholder} has its
 * own class, {@link ContentExtractorPartPlaceholderTest}.
 */
class ContentExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The bugs: the system prompt leaks into a {user, assistant} thread; an attachment silently vanishes
     * from the rendered turn instead of showing as a placeholder; a stray non-message element, a message
     * with no content, a scalar content node or a successful tool_result (no signal beyond the paired
     * call) adds an empty turn.
     */
    @Test
    void columnMessages_keepsListedRolesInOrderWithPlaceholdersAndDropsEmptyTurns() {
        String raw = """
                [
                 {"role":"system","content":"be terse"},
                 {"role":"user","content":[
                   {"type":"text","text":"read this"},
                   {"type":"image_url","image_url":{"url":"https://i/x.png"}},
                   {"type":"input_file","filename":"a.pdf"},
                   {"type":"audio","data":"AAAA"},
                   {"type":"refusal","text":"cannot help"}]},
                 "not-a-message",
                 {"role":"user","content":"plain question"},
                 {"role":"assistant","parts":[{"type":"text","content":"ok"}]},
                 {"role":"assistant","content":{"type":"tool_call","name":"search"}},
                 {"role":"user"},
                 {"role":"user","content":42},
                 {"role":"assistant","content":[{"type":"tool_result","content":"fine"}]}
                ]
                """;

        List<RoleMessage> messages = ContentExtractor.columnMessages(raw, Set.of("user", "assistant"), "user");

        assertEquals(
                List.of(
                        new RoleMessage("user", "read this [image] [file: a.pdf] [unsupported] cannot help"),
                        new RoleMessage("user", "plain question"),
                        new RoleMessage("assistant", "ok"),
                        new RoleMessage("assistant", "[tool:search ok]")),
                messages);
    }

    static Stream<Arguments> nonEnvelopeCases() {
        return Stream.of(
                Arguments.of(
                        "a plain-text blob from a provider adapter is one message from the column's speaker",
                        "  plain provider blob ",
                        List.of(new RoleMessage("assistant", "plain provider blob"))),
                Arguments.of(
                        "a bare JSON part is flattened to one message",
                        "{\"type\":\"text\",\"text\":\"hi\"}",
                        List.of(new RoleMessage("assistant", "hi"))),
                Arguments.of("an empty array adds no phantom turn", "[]", List.of()));
    }

    /** The bugs, one per row: a payload that is not a message envelope loses its text, or adds an empty turn. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonEnvelopeCases")
    void columnMessages_readsAPayloadThatIsNotAnEnvelope(String bug, String raw, List<RoleMessage> expected) {
        assertEquals(expected, ContentExtractor.columnMessages(raw, Set.of("user", "assistant"), "assistant"), bug);
    }

    static Stream<Arguments> columnTextCases() {
        return Stream.of(
                Arguments.of(
                        "a message with no content adds no blank line",
                        "[{\"role\":\"user\"},{\"role\":\"user\",\"content\":\"hi\"}]",
                        "user",
                        "hi"),
                Arguments.of(
                        "a bare parts array with no role is flattened, not read as an envelope",
                        "[{\"type\":\"text\",\"text\":\"hi\"}]",
                        "user",
                        "hi"),
                Arguments.of("a bare number payload renders as its text", "42", "user", "42"),
                Arguments.of(
                        "structured output inside a text part is kept, serialized",
                        "[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":{\"answer\":1}}]}]",
                        "assistant",
                        "{\"answer\":1}"),
                Arguments.of(
                        "structured content with no text field is kept as its JSON",
                        "[{\"role\":\"user\",\"content\":{\"foo\":1}}]",
                        "user",
                        "{\"foo\":1}"),
                Arguments.of(
                        "a numeric text field is skipped for the part's content",
                        "[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":5,\"content\":\"five\"}]}]",
                        "assistant",
                        "five"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columnTextCases")
    void columnText_flattensEachPayloadShape(String bug, String raw, String role, String expected) {
        assertEquals(expected, ContentExtractor.columnText(raw, role), bug);
    }

    static Stream<Arguments> blockCases() {
        return Stream.of(
                Arguments.of(
                        "a single part object (not an array) is a block",
                        "{\"type\":\"text\",\"text\":\"hi\"}",
                        List.of(ContentBlock.text("hi"))),
                Arguments.of("a bare string is one text block", "\"hello\"", List.of(ContentBlock.text("hello"))),
                Arguments.of("an empty string is no block", "\"\"", List.of()),
                Arguments.of(
                        "a null part and an empty text part add nothing",
                        "[null,{\"type\":\"text\",\"text\":\"\"}]",
                        List.of()),
                Arguments.of(
                        "an OpenAI image_url object keeps its url",
                        "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://i/c.png\"}}]",
                        List.of(new ContentBlock("image_url", null, "https://i/c.png", null, null))),
                Arguments.of(
                        "an image_url given as a bare string keeps it",
                        "[{\"type\":\"image_url\",\"image_url\":\"https://i/d.png\"}]",
                        List.of(new ContentBlock("image_url", null, "https://i/d.png", null, null))),
                Arguments.of("an image_url with no url is dropped", "[{\"type\":\"image_url\"}]", List.of()),
                Arguments.of(
                        "an Anthropic url-source image keeps its url",
                        "[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://i/e.png\"}}]",
                        List.of(new ContentBlock("image_url", null, "https://i/e.png", null, null))),
                Arguments.of(
                        "an Anthropic url-source document keeps its url",
                        "[{\"type\":\"document\",\"source\":{\"type\":\"url\",\"url\":\"https://f/d.pdf\"}}]",
                        List.of(new ContentBlock("document_url", null, "https://f/d.pdf", null, null))),
                Arguments.of(
                        "an Anthropic part with no source, or a source it does not read, is dropped",
                        "[{\"type\":\"image\"},{\"type\":\"document\",\"source\":{\"type\":\"text\",\"data\":\"x\"}}]",
                        List.of()),
                Arguments.of(
                        "a document_ref keeps its already-extracted text",
                        "[{\"type\":\"document_ref\",\"data\":\"m1\",\"mediaType\":\"application/pdf\","
                                + "\"text\":\"extracted words\"}]",
                        List.of(new ContentBlock("document_ref", "extracted words", null, "m1", "application/pdf"))),
                Arguments.of(
                        "an OpenAI input_image keeps its url",
                        "[{\"type\":\"input_image\",\"image_url\":\"https://i/a.png\"}]",
                        List.of(new ContentBlock("image_url", null, "https://i/a.png", null, null))),
                Arguments.of(
                        "an output_image with only a url field keeps it",
                        "[{\"type\":\"output_image\",\"url\":\"https://i/b.png\"}]",
                        List.of(new ContentBlock("image_url", null, "https://i/b.png", null, null))),
                Arguments.of("an input_image with no url is dropped", "[{\"type\":\"input_image\"}]", List.of()),
                Arguments.of(
                        "a base64 data URI file is a PDF block, prefix matched case-insensitively",
                        "[{\"type\":\"input_file\",\"file_data\":\"DATA:application/pdf;base64,QUJD\"}]",
                        List.of(new ContentBlock("document_b64", null, null, "QUJD", "application/pdf"))),
                Arguments.of(
                        "a data URI with no mime defaults to application/pdf",
                        "[{\"type\":\"file\",\"file_data\":\"data:;base64,QUJD\"}]",
                        List.of(new ContentBlock("document_b64", null, null, "QUJD", "application/pdf"))),
                Arguments.of(
                        "a data URI with no comma falls back to the file_url",
                        "[{\"type\":\"input_file\",\"file_data\":\"data:application/pdf;base64\","
                                + "\"file_url\":\"https://f/a.pdf\"}]",
                        List.of(new ContentBlock("document_url", null, "https://f/a.pdf", null, null))),
                Arguments.of(
                        "a data URI that is not base64 falls back to the url",
                        "[{\"type\":\"input_file\",\"file_data\":\"data:text/plain,hello\",\"url\":\"https://f/b.pdf\"}]",
                        List.of(new ContentBlock("document_url", null, "https://f/b.pdf", null, null))),
                Arguments.of(
                        "file_data that is not a data URI falls back to the url",
                        "[{\"type\":\"input_file\",\"file_data\":\"https://f/c.pdf\",\"url\":\"https://f/c.pdf\"}]",
                        List.of(new ContentBlock("document_url", null, "https://f/c.pdf", null, null))),
                Arguments.of(
                        "a Files-API file_id stays visible as JSON text, not dropped",
                        "[{\"type\":\"input_file\",\"file_id\":\"file-123\"}]",
                        List.of(ContentBlock.text("{\"type\":\"input_file\",\"file_id\":\"file-123\"}"))),
                Arguments.of(
                        "an unknown part type stays visible as JSON text, not dropped",
                        "[{\"type\":\"audio\",\"data\":\"AAAA\"}]",
                        List.of(ContentBlock.text("{\"type\":\"audio\",\"data\":\"AAAA\"}"))),
                Arguments.of(
                        "an Anthropic image with an empty media_type is served as image/png",
                        "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"\",\"data\":\"QQ==\"}}]",
                        List.of(new ContentBlock("image_b64", null, null, "QQ==", "image/png"))),
                Arguments.of(
                        "an Anthropic document with an empty media_type is served as application/pdf",
                        "[{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"\",\"data\":\"QQ==\"}}]",
                        List.of(new ContentBlock("document_b64", null, null, "QQ==", "application/pdf"))),
                Arguments.of(
                        "an image_ref with an empty mediaType is served as image/png",
                        "[{\"type\":\"image_ref\",\"data\":\"m2\",\"mediaType\":\"\"}]",
                        List.of(new ContentBlock("image_ref", null, null, "m2", "image/png"))),
                Arguments.of(
                        "a document_ref with an empty mediaType is served as application/pdf",
                        "[{\"type\":\"document_ref\",\"data\":\"m1\",\"mediaType\":\"\"}]",
                        List.of(new ContentBlock("document_ref", null, null, "m1", "application/pdf"))));
    }

    /**
     * The bugs, one per row: an image or document part lost from the trace viewer and the judge, or
     * stored with an empty content type that the media serve endpoint then answers with.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("blockCases")
    void blocksFromContent_typesEachPartShape(String bug, String json, List<ContentBlock> expected) throws Exception {
        assertEquals(expected, ContentExtractor.blocksFromContent(MAPPER.readTree(json)), bug);
    }
}

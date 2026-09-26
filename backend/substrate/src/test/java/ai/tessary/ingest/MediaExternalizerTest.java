// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Base64 image content is externalized to a {@code media_object} ref at ingest — the persisted
 * substrate never carries inline base64. Covers the JSON-payload rewrite (observation/tool_call
 * content).
 */
class MediaExternalizerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private MediaStore media;
    private MediaExternalizer externalizer;

    @BeforeEach
    void setUp() {
        media = mock(MediaStore.class);
        when(media.put(anyString(), any(), anyString())).thenReturn(new MediaRef("media-1"));
        externalizer = new MediaExternalizer(media, M);
    }

    @Test
    void anthropicBase64Image_becomesImageRef_bytesStored() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String json = "[{\"type\":\"text\",\"text\":\"look\"},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\""
                + b64 + "\"}}]";

        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);
        String out = java.util.Objects.requireNonNull(result.payload());

        var arr = M.readTree(out);
        assertEquals("image_ref", arr.get(1).path("type").asText());
        assertEquals("media-1", arr.get(1).path("data").asText());
        assertEquals("image/jpeg", arr.get(1).path("mediaType").asText());
        assertFalse(out.contains(b64), "no base64 may survive into the persisted payload");
        assertEquals(
                List.of("media-1"),
                result.mediaIds(),
                "the caller must learn the id, or the media_ref row that makes the bytes reachable"
                        + " and collectable is never written");

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(media).put(eq("p1"), stored.capture(), eq("image/jpeg"));
        assertEquals(4, stored.getValue().length);
    }

    @Test
    void openAiDataUriImageUrl_becomesImageRef() throws Exception {
        byte[] bytes = {9, 8, 7};
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        String json = "[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUri
                + "\"}}]}]";

        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);
        String out = java.util.Objects.requireNonNull(result.payload());

        // The image_url part is replaced wholesale by an image_ref node.
        var part = M.readTree(out).get(0).path("content").get(0);
        assertEquals("image_ref", part.path("type").asText());
        assertEquals("media-1", part.path("data").asText());
        assertEquals(List.of("media-1"), result.mediaIds());
        verify(media).put(eq("p1"), any(), eq("image/png"));
    }

    @Test
    void malformedJson_returnedVerbatim() {
        String junk = "{not valid";
        assertSame(junk, externalizer.externalizeJson("p1", junk).payload());
    }

    @Test
    void anthropicBase64Document_becomesDocumentRef_textCarriesFailureMarker() throws Exception {
        // Garbage bytes, not a real PDF (PdfTextExtractorTest owns real extraction) — proves that when
        // extraction fails, the ref is still minted with a labeled failure marker, never a fatal batch.
        byte[] notAPdf = {1, 2, 3, 4};
        String b64 = Base64.getEncoder().encodeToString(notAPdf);
        String json = "[{\"type\":\"text\",\"text\":\"see attached\"},"
                + "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\""
                + b64 + "\"}}]";

        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);
        String out = java.util.Objects.requireNonNull(result.payload());

        var node = M.readTree(out).get(1);
        assertEquals("document_ref", node.path("type").asText());
        assertEquals("media-1", node.path("data").asText());
        assertEquals("application/pdf", node.path("mediaType").asText());
        assertEquals(
                MediaExternalizer.DOCUMENT_TEXT_UNAVAILABLE_MARKER,
                node.path("text").asText());
        assertFalse(out.contains(b64), "no base64 may survive into the persisted payload");
        assertEquals(List.of("media-1"), result.mediaIds());
        verify(media).put(eq("p1"), any(), eq("application/pdf"));
    }

    @Test
    void openAiInputFileDataUri_becomesDocumentRef() throws Exception {
        byte[] notAPdf = {9, 8, 7};
        String dataUri = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(notAPdf);
        String json = "[{\"role\":\"user\",\"content\":[{\"type\":\"input_file\",\"file_data\":\"" + dataUri + "\"}]}]";

        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);
        String out = java.util.Objects.requireNonNull(result.payload());

        var part = M.readTree(out).get(0).path("content").get(0);
        assertEquals("document_ref", part.path("type").asText());
        assertEquals("media-1", part.path("data").asText());
        assertEquals(List.of("media-1"), result.mediaIds());
        verify(media).put(eq("p1"), any(), eq("application/pdf"));
    }

    @Test
    void httpFileUrl_isLeftUntouched_noStore() {
        String json = "[{\"type\":\"file\",\"file_url\":\"https://example.com/report.pdf\"}]";
        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);
        assertSame(json, result.payload(), "a payload with no inline base64 is returned byte-identical");
        verify(media, never()).put(anyString(), any(), anyString());
    }

    /**
     * A media part that points at a URL, or whose inline bytes do not decode, has nothing to store: the
     * payload is kept byte for byte and names no media, rather than failing the span or storing garbage.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x.test/a.png\"}}]",
                "[{\"type\":\"document\",\"source\":{\"type\":\"url\",\"url\":\"https://x.test/a.pdf\"}}]",
                "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\"!!not base64!!\"}}]",
                "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,@@@\"}}]",
                "[{\"type\":\"input_image\",\"url\":\"https://x.test/b.png\"}]"
            })
    void mediaWithNothingToStore_isLeftInline(String json) {
        assertEquals(MediaExternalizer.Externalized.unchanged(json), externalizer.externalizeJson("p1", json));
    }

    /** OpenAI also sends {@code image_url} as a bare string; its data URI is externalized all the same. */
    @Test
    void openAiBareStringImageUrl_becomesImageRef() throws Exception {
        String dataUri = "data:image/gif;base64," + Base64.getEncoder().encodeToString(new byte[] {5, 6});
        String json = "[{\"type\":\"image_url\",\"image_url\":\"" + dataUri + "\"}]";

        MediaExternalizer.Externalized result = externalizer.externalizeJson("p1", json);

        var node =
                M.readTree(java.util.Objects.requireNonNull(result.payload())).get(0);
        assertEquals("image_ref", node.path("type").asText());
        assertEquals("image/gif", node.path("mediaType").asText());
        assertEquals(List.of("media-1"), result.mediaIds());
    }

    /** A media store that is down costs the span its externalization, not the span itself. */
    @Test
    void aFailingStore_leavesTheMediaInlineAndNamesNoMedia() {
        when(media.put(anyString(), any(), anyString())).thenThrow(new IllegalStateException("bucket unreachable"));
        String json = "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\""
                + Base64.getEncoder().encodeToString(new byte[] {1, 2}) + "\"}}]";

        assertEquals(MediaExternalizer.Externalized.unchanged(json), externalizer.externalizeJson("p1", json));
    }
}

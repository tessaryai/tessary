// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shaped multi-modal content unit: {@code text}, {@code image_url}, {@code image_b64}, {@code image_ref},
 * {@code document_b64}, {@code document_ref} or {@code document_url}. URLs are display-only and never fetched
 * server-side (see {@code devdocs/reference/media-contract.md}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentBlock(
        @Schema(
                allowableValues = {
                    "text",
                    "image_url",
                    "image_b64",
                    "image_ref",
                    "document_b64",
                    "document_ref",
                    "document_url"
                })
        String type,

        @Nullable String text,
        @Nullable String url,
        @Nullable String data,
        @Nullable String mediaType) {
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";
    public static final String TYPE_IMAGE_B64 = "image_b64";
    /**
     * {@code data} carries a {@link ai.tessary.open.media.MediaStore} id, not base64; inline base64 is never persisted
     * into {@code span_payload}.
     */
    public static final String TYPE_IMAGE_REF = "image_ref";

    public static final String TYPE_DOCUMENT_B64 = "document_b64";

    /** {@code data} is the media id; {@code text} is the extracted PDF text or a labeled failure marker. */
    public static final String TYPE_DOCUMENT_REF = "document_ref";

    /** Display-only: never fetched server-side for extraction. */
    public static final String TYPE_DOCUMENT_URL = "document_url";

    public static ContentBlock text(String t) {
        return new ContentBlock(TYPE_TEXT, t, null, null, null);
    }

    public static ContentBlock imageUrl(String u) {
        return new ContentBlock(TYPE_IMAGE_URL, null, u, null, null);
    }

    public static ContentBlock imageB64(String data, String mediaType) {
        return new ContentBlock(
                TYPE_IMAGE_B64, null, null, data, mediaType == null || mediaType.isBlank() ? "image/png" : mediaType);
    }

    public static ContentBlock imageRef(String mediaId, String mediaType) {
        return new ContentBlock(
                TYPE_IMAGE_REF,
                null,
                null,
                mediaId,
                mediaType == null || mediaType.isBlank() ? "image/png" : mediaType);
    }

    /** Defaults {@code mediaType} to {@code application/pdf} when blank. */
    public static ContentBlock documentB64(String data, @Nullable String mediaType) {
        return new ContentBlock(
                TYPE_DOCUMENT_B64,
                null,
                null,
                data,
                mediaType == null || mediaType.isBlank() ? "application/pdf" : mediaType);
    }

    /** {@code text} is null here; {@code MediaExternalizer} fills it before persisting. */
    public static ContentBlock documentRef(String mediaId, @Nullable String mediaType) {
        return new ContentBlock(
                TYPE_DOCUMENT_REF,
                null,
                null,
                mediaId,
                mediaType == null || mediaType.isBlank() ? "application/pdf" : mediaType);
    }

    public static ContentBlock documentUrl(String u) {
        return new ContentBlock(TYPE_DOCUMENT_URL, null, u, null, null);
    }

    /**
     * Separate from {@link #isMedia()} because an image is a first-class part on the wire while a document flattens to
     * text. {@link JsonIgnore} so the derived predicate does not leak an {@code "image"} property that would fail to
     * round-trip.
     */
    @JsonIgnore
    public boolean isImage() {
        return TYPE_IMAGE_URL.equals(type) || TYPE_IMAGE_B64.equals(type) || TYPE_IMAGE_REF.equals(type);
    }

    @JsonIgnore
    public boolean isDocument() {
        return TYPE_DOCUMENT_B64.equals(type) || TYPE_DOCUMENT_REF.equals(type) || TYPE_DOCUMENT_URL.equals(type);
    }

    @JsonIgnore
    public boolean isMedia() {
        return isImage() || isDocument();
    }
}

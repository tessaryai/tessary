// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shaped multi-modal content unit. Block types:
 *   - text          → plain text in the user message
 *   - image_url     → http(s) URL OR data: URI
 *   - image_b64     → raw base64 + media type (no data: prefix)
 *   - image_ref     → externalized image (media_object id in {@code data})
 *   - document_b64  → raw base64 PDF + media type (mirrors image_b64)
 *   - document_ref  → externalized document (media_object id in {@code data}); {@code text} carries
 *     the extracted PDF text (or a labeled failure marker), populated at ingest — see
 *     {@code MediaExternalizer}.
 *   - document_url  → http(s) URL, mirroring {@code image_url}'s field layout exactly. Display-only:
 *     never fetched server-side (ingest or export) — a deliberate decision, not
 *     an omission (documented in {@code devdocs/reference/media-contract.md}).
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
     * An externalized image: {@code data} carries a {@link ai.tessary.open.media.MediaStore}
     * media-object id (NOT base64), resolved to bytes on demand. Inline base64 is never persisted into
     * {@code span_payload.input}/{@code .output} — the bytes live in {@code media_object} and are
     * served by id; readers that need the actual bytes (the frontend viewer) re-hydrate
     * through the MediaStore / the media serve endpoint.
     */
    public static final String TYPE_IMAGE_REF = "image_ref";

    /** A raw base64 PDF + media type (mirrors {@link #TYPE_IMAGE_B64}'s shape exactly). */
    public static final String TYPE_DOCUMENT_B64 = "document_b64";

    /** An externalized document. {@code data} = the media id; {@code text} = extracted PDF text (or a
     *  labeled failure marker). */
    public static final String TYPE_DOCUMENT_REF = "document_ref";

    /** A document referenced by URL, mirroring {@link #TYPE_IMAGE_URL}'s field layout exactly
     *  ({@code url} holds the real URL). Display-only: never fetched server-side for extraction. */
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

    /** An externalized image referencing a {@code media_object} id. {@code data} = the media id. */
    public static ContentBlock imageRef(String mediaId, String mediaType) {
        return new ContentBlock(
                TYPE_IMAGE_REF,
                null,
                null,
                mediaId,
                mediaType == null || mediaType.isBlank() ? "image/png" : mediaType);
    }

    /** A raw base64 PDF, mirroring {@link #imageB64}: defaults {@code mediaType} to
     *  {@code application/pdf} when blank. */
    public static ContentBlock documentB64(String data, @Nullable String mediaType) {
        return new ContentBlock(
                TYPE_DOCUMENT_B64,
                null,
                null,
                data,
                mediaType == null || mediaType.isBlank() ? "application/pdf" : mediaType);
    }

    /** An externalized document referencing a {@code media_object} id. {@code data} = the media id;
     *  {@code text} is null here (the caller — {@code MediaExternalizer} — fills it with extracted text
     *  or a failure marker before persisting). */
    public static ContentBlock documentRef(String mediaId, @Nullable String mediaType) {
        return new ContentBlock(
                TYPE_DOCUMENT_REF,
                null,
                null,
                mediaId,
                mediaType == null || mediaType.isBlank() ? "application/pdf" : mediaType);
    }

    /** A document referenced by URL. Display-only — never fetched server-side; see
     *  {@link #TYPE_DOCUMENT_URL}. */
    public static ContentBlock documentUrl(String u) {
        return new ContentBlock(TYPE_DOCUMENT_URL, null, u, null, null);
    }

    /**
     * True when this block carries an image ({@code image_url}, {@code image_b64} or {@code image_ref}).
     * {@link #isMedia()} is the single source of truth for "is this a media block";
     * {@code isImage()} stays a separate, narrower predicate because the two modalities are shaped
     * differently on the wire: an image is a first-class image part, a document flattens to text. It
     * USED to carry a second consequence — at the judge's request build an image forced Bedrock
     * system-block caching off while a document did not — but grading is gone now and that
     * boundary with it, so the distinction now describes the block alone.
     *
     * <p>{@link JsonIgnore}: this is a derived predicate over {@code type}, not a wire field. The
     * substrate serializes {@code ContentBlock[]} to {@code span_payload.input}/{@code .output}, so the
     * accessor must not leak an {@code "image"} property into the JSON (which would then fail to
     * round-trip back into the canonical five fields).
     */
    @JsonIgnore
    public boolean isImage() {
        return TYPE_IMAGE_URL.equals(type) || TYPE_IMAGE_B64.equals(type) || TYPE_IMAGE_REF.equals(type);
    }

    /** True when this block carries a document ({@code document_b64}, {@code document_ref} or
     *  {@code document_url}). See {@link #isImage()} for why this is a separate predicate from
     *  {@link #isMedia()} rather than folded into it. */
    @JsonIgnore
    public boolean isDocument() {
        return TYPE_DOCUMENT_B64.equals(type) || TYPE_DOCUMENT_REF.equals(type) || TYPE_DOCUMENT_URL.equals(type);
    }

    /** True when this block carries any media (image or document) — the single source of truth for
     *  "is this a media block" that {@link #isImage()}'s doc refers callers to. */
    @JsonIgnore
    public boolean isMedia() {
        return isImage() || isDocument();
    }
}

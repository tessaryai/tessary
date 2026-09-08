// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shaped multi-modal content unit. Block types:
 *   - text          → plain text in the user message
 *   - image_url     → http(s) URL OR data: URI (LangChain4j ImageContent accepts both)
 *   - image_b64     → raw base64 + media type (no data: prefix)
 *   - image_ref     → externalized image (media_object id in {@code data})
 *   - document_b64  → raw base64 PDF + media type (mirrors image_b64)
 *   - document_ref  → externalized document (media_object id in {@code data}); {@code text} carries
 *     the extracted PDF text (or a labeled failure marker), populated at ingest — see
 *     {@code MediaExternalizer}. Display-only filename input never survives past ingest: see
 *     {@link #documentB64(String, String, String)}.
 *   - document_url  → http(s) URL, mirroring {@code image_url}'s field layout exactly. Display-only:
 *     never fetched server-side (ingest, the judge boundary, or export) — a deliberate decision, not
 *     an omission (documented in {@code docs/reference/media-contract.md}).
 *   - tool_call / tool_result / reasoning → structured gen_ai parts: {@code text} is a readable
 *     summary (what the judge/model sees when flattened) and {@code data} carries the structured
 *     JSON payload (the tool_call/tool_result part), persisted to {@code span_payload.input}/
 *     {@code .output} jsonb.
 *
 * The underlying ChatModel (LangChain4j against an OpenAI-wire endpoint) has no first-class
 * tool-call Content type, so the judge boundary flattens the structured types back to text.
 *
 * <p><b>Filename vs. extracted text on {@code document_ref} ({@code text}).</b> A provider-supplied
 * filename can be stashed in {@code text} only transiently, before ingest externalizes the block
 * ({@link #documentB64(String, String, String)} / {@link #documentRef(String, String, String)}
 * overloads). {@code MediaExternalizer} always overwrites {@code text} with the extracted PDF text (or
 * a failure marker) before persisting a {@code document_ref} node, so the filename and the extracted
 * text never ride the same block downstream of ingest — a reader of a persisted {@code document_ref}
 * should expect extracted text in {@code text}, never a filename.
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
                    "document_url",
                    "tool_call",
                    "tool_result",
                    "reasoning"
                })
        String type,

        @Nullable String text,
        @Nullable String url,
        @Nullable String data,
        @Nullable String mediaType) {
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";
    public static final String TYPE_IMAGE_B64 = "image_b64";
    /** Structured gen_ai part types (typed message blocks); {@code data} carries the JSON payload. */
    public static final String TYPE_TOOL_CALL = "tool_call";

    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_REASONING = "reasoning";
    /**
     * An externalized image: {@code data} carries a {@link ai.tessary.evals.open.media.MediaStore}
     * media-object id (NOT base64), resolved to bytes on demand. Inline base64 is never persisted into
     * {@code span_payload.input}/{@code .output} — the bytes live in {@code media_object} and are
     * served by id; readers that need the actual bytes (the judge boundary, the frontend viewer)
     * re-hydrate through the MediaStore / the media serve endpoint.
     */
    public static final String TYPE_IMAGE_REF = "image_ref";

    /** A raw base64 PDF + media type (mirrors {@link #TYPE_IMAGE_B64}'s shape exactly). */
    public static final String TYPE_DOCUMENT_B64 = "document_b64";

    /** An externalized document. {@code data} = the media id; {@code text} = extracted PDF text (or a
     *  labeled failure marker), never a filename downstream of ingest — see the class doc. */
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

    /** {@link #documentB64(String, String)} with a provider-supplied filename, stashed transiently in
     *  {@code text} — a caller that has not yet gone through {@code MediaExternalizer} (which
     *  overwrites {@code text} with extracted content before persisting). Never both filename and
     *  extracted text on the same block; see the class doc. */
    public static ContentBlock documentB64(String data, @Nullable String mediaType, @Nullable String filename) {
        ContentBlock b = documentB64(data, mediaType);
        return filename == null || filename.isBlank()
                ? b
                : new ContentBlock(b.type(), filename, null, data, b.mediaType());
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

    /** {@link #documentRef(String, String)} with a provider-supplied filename, stashed transiently in
     *  {@code text} — see the class doc's filename-vs-extracted-text note. */
    public static ContentBlock documentRef(String mediaId, @Nullable String mediaType, @Nullable String filename) {
        ContentBlock b = documentRef(mediaId, mediaType);
        return filename == null || filename.isBlank()
                ? b
                : new ContentBlock(b.type(), filename, null, mediaId, b.mediaType());
    }

    /** A document referenced by URL. Display-only — never fetched server-side; see
     *  {@link #TYPE_DOCUMENT_URL}. */
    public static ContentBlock documentUrl(String u) {
        return new ContentBlock(TYPE_DOCUMENT_URL, null, u, null, null);
    }

    /** A tool invocation. {@code summary} = readable text (name + args); {@code json} = the structured
     *  {@code {id,name,arguments}} payload (persisted to {@code span_payload} jsonb). */
    public static ContentBlock toolCall(String summary, @Nullable String json) {
        return new ContentBlock(TYPE_TOOL_CALL, summary, null, json, null);
    }

    /** A tool result. {@code content} = readable text; {@code json} = the structured {@code {id,content}} payload. */
    public static ContentBlock toolResult(String content, @Nullable String json) {
        return new ContentBlock(TYPE_TOOL_RESULT, content, null, json, null);
    }

    /** A model reasoning/thinking block. */
    public static ContentBlock reasoning(String text) {
        return new ContentBlock(TYPE_REASONING, text, null, null, null);
    }

    /**
     * True when this block carries an image ({@code image_url}, {@code image_b64} or {@code image_ref}).
     * {@link #isMedia()} is the single source of truth for "is this a media block" — {@code
     * ContentExtractor.hasMedia} delegates to it so callers can never drift on what counts as media.
     * {@code isImage()} stays a separate, narrower predicate because the two modalities are shaped
     * differently on the wire: an image is a first-class image part, a document flattens to text. It
     * USED to carry a second consequence — at the judge's request build an image forced Bedrock
     * system-block caching off while a document did not — but Track A removed grading and that
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

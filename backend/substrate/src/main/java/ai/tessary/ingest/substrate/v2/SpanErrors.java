// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.model.ErrorSignature;
import org.jspecify.annotations.Nullable;

/**
 * Derives a failing span's {@code error_type} (the CLASS: OTel {@code error.type}, else a normalized signature of the
 * status message) and {@code error_message} (the prose), shared by {@link SpanBatchWriter} and {@link SpanSideTables}.
 * Facets group by the class, so it must never hold free prose.
 */
final class SpanErrors {

    private SpanErrors() {}

    /**
     * A poison status message must not bloat the hot table; the full text survives in {@code span_payload.attributes}.
     */
    static final int MAX_ERROR_MESSAGE_CHARS = 2_000;

    /**
     * Null when the producer said nothing. The declared attribute is capped at
     * {@link ErrorSignature#MAX_SIGNATURE_LENGTH} too, since nothing upstream stops a producer putting prose there.
     */
    static @Nullable String errorClass(@Nullable String declaredType, @Nullable String statusMessage) {
        if (declaredType != null && !declaredType.isBlank()) return cap(declaredType);
        return statusMessage == null || statusMessage.isBlank() ? null : ErrorSignature.signature(statusMessage);
    }

    private static String cap(String declaredType) {
        String trimmed = declaredType.trim();
        return trimmed.length() <= ErrorSignature.MAX_SIGNATURE_LENGTH
                ? trimmed
                : trimmed.substring(0, ErrorSignature.MAX_SIGNATURE_LENGTH).trim() + "…";
    }

    static @Nullable String cappedMessage(@Nullable String statusMessage) {
        if (statusMessage == null || statusMessage.isBlank()) return null;
        return statusMessage.length() <= MAX_ERROR_MESSAGE_CHARS
                ? statusMessage
                : statusMessage.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

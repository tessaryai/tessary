// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import ai.tessary.evals.model.ErrorSignature;
import org.jspecify.annotations.Nullable;

/**
 * How a failing span's two error columns are derived, in one place because two writers derive them: the
 * span itself ({@link SpanBatchWriter}) and the {@code tool_call} hung off it ({@link SpanSideTables}).
 *
 * <p><b>The split (#762).</b> {@code error_type} used to be written from the producer's
 * {@code statusMessage} — free prose, up to 3,271 characters of agent markdown on the corpus that
 * surfaced this — in the column every facet, breakdown and {@code GROUP BY} in the product treats as a
 * type. It now holds the CLASS: the producer's own {@code error.type} attribute, which is what OTel
 * defines that attribute to be, and the prose moves to {@code error_message}.
 *
 * <p><b>The fallback is a signature, not a truncation.</b> A producer that ships no {@code error.type}
 * would otherwise leave the column null and take its failures out of every breakdown. Normalizing the
 * status message instead keeps the row groupable with its kin, through the same function the tool-error
 * classifier groups by — one definition of "these two errors are the same kind", not two.
 */
final class SpanErrors {

    private SpanErrors() {}

    /**
     * Cap for the prose column. Not a truncation policy for telemetry at large — payloads are bounded by
     * count and never clipped — but this column exists to be read beside a row, and a poison status
     * message must not be able to bloat the hot table every list and rollup scans. The full text survives
     * verbatim in {@code span_payload.attributes} either way.
     */
    static final int MAX_ERROR_MESSAGE_CHARS = 2_000;

    /**
     * The class of a failure: what the producer named it, else a capped signature of what it said, else
     * null when it said nothing at all (the row's {@code status}/{@code level} still carry the failure).
     *
     * <p>The declared attribute is capped too, at {@link ErrorSignature#MAX_SIGNATURE_LENGTH} — the same
     * bound the fallback signature carries. {@code error.type} is documented everywhere as a short facet
     * key, but nothing upstream enforces that on the wire, and a producer is free to put prose there; an
     * uncapped declared value would let one poorly-behaved producer reopen the exact bloated-facet-key
     * problem #762 exists to close.
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

    /** The prose of a failure, capped at {@link #MAX_ERROR_MESSAGE_CHARS}. */
    static @Nullable String cappedMessage(@Nullable String statusMessage) {
        if (statusMessage == null || statusMessage.isBlank()) return null;
        return statusMessage.length() <= MAX_ERROR_MESSAGE_CHARS
                ? statusMessage
                : statusMessage.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

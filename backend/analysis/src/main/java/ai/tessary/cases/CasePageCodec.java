// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.cases.CaseRepository.PageKey;
import ai.tessary.cases.CaseRepository.PageOrder;
import ai.tessary.ingest.PreviewCursor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The case list's paging vocabulary: the opaque keyset cursor, and the over-fetch-by-one next-page detection
 * that mints it. The trace list's {@code TracePageCodec} is the model, down to the separator character.
 *
 * <p><b>The cursor carries the order it was minted under, and a mismatch discards it.</b> A page of cases is
 * ranked one of two ways depending on the {@code state} filter ({@link CaseRepository#orderFor}), and the two
 * keys are not the same columns — the live key is {@code (severity, opened_at, id)}, the resolved key is
 * {@code (resolved_at, id)}. So a cursor minted on {@code state=open} and replayed against
 * {@code state=resolved} does not describe a point on the new ordering at all: resumed blindly it would
 * compare an {@code opened_at} against {@code resolved_at} and return an arbitrary slice that reads like a
 * page. Stamping the order and dropping a token that disagrees turns that into the one failure a feed can
 * absorb quietly — restarting at page one.
 */
final class CasePageCodec {

    /**
     * Cursor key separator: the unit-separator control char, which cannot appear in an ISO-8601 timestamp, a
     * ULID, or a formatted double, so the split is unambiguous. Same character the trace and session cursors
     * pack with — "which control char was it" should never be a question two files answer separately.
     */
    private static final char SEP = '\u001f';

    /** The cursor generation. A token from any other shape is unreadable rather than mis-resumed. */
    private static final String VERSION = "v1";

    private CasePageCodec() {}

    /** A trimmed page and the cursor that resumes after it — null when this was the last page. */
    record Page(List<CaseRow> rows, @Nullable String nextCursor) {}

    /**
     * The keyset key a cursor resumes from, or null for "start at the top".
     *
     * <p>Null covers every unusable token — absent, corrupt, a foreign generation, and a cursor minted under
     * the other ordering — because there is nothing a caller could do differently about any of them and page
     * one is a correct answer to all four.
     */
    static @Nullable PageKey decode(@Nullable String cursor, PageOrder order) {
        String token = PreviewCursor.decode(cursor).token();
        if (token == null) {
            return null;
        }
        String[] parts = token.split(String.valueOf(SEP), -1);
        if (parts.length != 5 || !VERSION.equals(parts[0]) || !order.name().equals(parts[1])) {
            return null;
        }
        Double severity = null;
        if (!parts[2].isEmpty()) {
            try {
                severity = Double.valueOf(parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // The live order ranks by severity first, so a token for it that carries none names no point on the
        // ordering. Page one rather than a query with a null bound, which Postgres would answer with nothing.
        if (order == PageOrder.LIVE_RANK && severity == null) {
            return null;
        }
        if (parts[3].isEmpty() || parts[4].isEmpty()) {
            return null;
        }
        return new PageKey(severity, parts[3], parts[4]);
    }

    /**
     * Trim an over-fetched result to {@code pageSize} and mint the cursor for the next page.
     *
     * <p>The caller asked the repository for {@code pageSize + 1} rows. More than {@code pageSize} came back,
     * so there is another page and the cursor is seeded from the LAST ROW OF THIS PAGE (index
     * {@code pageSize - 1}), never from the extra row — the extra row is the first row of the next page, and
     * seeding from it would skip it.
     */
    static Page trim(List<CaseRow> overFetched, int pageSize, PageOrder order) {
        if (overFetched.size() <= pageSize) {
            return new Page(overFetched, null);
        }
        CaseRow last = overFetched.get(pageSize - 1);
        return new Page(List.copyOf(overFetched.subList(0, pageSize)), encode(last, order));
    }

    private static @Nullable String encode(CaseRow last, PageOrder order) {
        String at = order == PageOrder.LIVE_RANK ? last.openedAt() : last.resolvedAt();
        // Unreachable: ck_eval_case_resolved_stamp makes resolved_at NOT NULL for exactly the rows the
        // resolved order selects. No cursor rather than one bounded on "null": the page simply ends here,
        // because a missing next_cursor loses a page and a lying one loses the reader's trust.
        if (at == null) {
            return null;
        }
        String severity = order == PageOrder.LIVE_RANK ? Double.toString(last.severity()) : "";
        return PreviewCursor.encode(VERSION + SEP + order.name() + SEP + severity + SEP + at + SEP + last.id(), 0);
    }
}

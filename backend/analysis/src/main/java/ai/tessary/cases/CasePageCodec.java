// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.cases.CaseRepository.PageKey;
import ai.tessary.cases.CaseRepository.PageOrder;
import ai.tessary.ingest.PreviewCursor;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The case list's keyset cursor, modelled on {@code TracePageCodec}. The cursor carries the order it was minted under
 * ({@link CaseRepository#orderFor}); one replayed under the other order is discarded rather than compare
 * {@code opened_at} against {@code resolved_at}.
 */
final class CasePageCodec {

    /** Cannot appear in a timestamp, ULID or formatted double. The same character as the trace and session cursors. */
    private static final char SEP = '\u001f';

    /** A token from any other shape is unreadable rather than mis-resumed. */
    private static final String VERSION = "v1";

    private CasePageCodec() {}

    record Page(List<CaseRow> rows, @Nullable String nextCursor) {}

    /**
     * Null for every unusable token (absent, corrupt, foreign generation, other ordering): page one is correct for all
     * of them.
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
        // A null bound would make Postgres return nothing.
        if (order == PageOrder.LIVE_RANK && severity == null) {
            return null;
        }
        if (parts[3].isEmpty() || parts[4].isEmpty()) {
            return null;
        }
        return new PageKey(severity, parts[3], parts[4]);
    }

    /**
     * Trim a {@code pageSize + 1} result. The cursor is seeded from the last row of this page, never the extra row,
     * which is the next page's first.
     */
    static Page trim(List<CaseRow> overFetched, int pageSize, PageOrder order) {
        if (overFetched.size() <= pageSize) {
            return new Page(overFetched, null);
        }
        CaseRow last = overFetched.get(pageSize - 1);
        return new Page(List.copyOf(overFetched.subList(0, pageSize)), encode(last, order));
    }

    private static String encode(CaseRow last, PageOrder order) {
        // ck_eval_case_resolved_stamp makes resolved_at NOT NULL for exactly the rows the resolved order selects.
        String at = order == PageOrder.LIVE_RANK
                ? last.openedAt()
                : Objects.requireNonNull(last.resolvedAt(), "resolved row without resolved_at");
        String severity = order == PageOrder.LIVE_RANK ? Double.toString(last.severity()) : "";
        return PreviewCursor.encode(VERSION + SEP + order.name() + SEP + severity + SEP + at + SEP + last.id(), 0);
    }
}

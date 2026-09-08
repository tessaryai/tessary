// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.traces;

import ai.tessary.evals.ingest.PreviewCursor;
import ai.tessary.evals.storage.TraceV2Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The trace list's paging vocabulary: the opaque keyset cursor, the over-fetch-by-one next-page detection,
 * and the limit clamp. One implementation, because there are now two callers — {@link TracesController} and
 * the MCP {@code list_traces} reader — and a cursor is exactly the kind of thing that drifts silently when
 * copied: two encoders that disagree by one field produce tokens each side accepts and mis-resumes from.
 *
 * <p><b>The over-fetch belongs here too.</b> "Ask for one more row than the page, and if it arrives there is
 * a next page" is not an implementation detail of one controller — it is the only reason this API needs no
 * {@code COUNT} query per page. {@link #trim} does that and mints the cursor in the same step, so a caller
 * cannot do half of it.
 */
public final class TracePageCodec {

    /**
     * Cursor key separator: a unit-separator control char packed into the opaque {@link PreviewCursor}
     * token. It cannot collide with an ISO-8601 timestamp or a hex id, so the split is unambiguous.
     *
     * <p>Package-visible because {@link SessionReadService} packs its own two-part session key with the same
     * character for the same reason. One definition, so "which control char was it" is never a question two
     * files answer separately.
     */
    static final char SEP = '\u001f';

    /**
     * The cursor generation. A v1 cursor carries a surrogate ULID and a {@code COALESCE(started_at,
     * created_at)} instant, neither of which means anything against the v2 keyset — so rather than
     * resuming from a point that is not on the ordering, an unversioned token is discarded and the reader
     * gets page one. Degrading to the newest page is the only failure mode a feed can absorb silently.
     */
    private static final String VERSION = "v2";

    private TracePageCodec() {}

    /** The decoded keyset key, or all-null for "start at the newest". */
    public record Key(
            @Nullable String sortValue,
            @Nullable String startedAt,
            @Nullable String id) {
        public static final Key NONE = new Key(null, null, null);
    }

    /** A trimmed page and the cursor that resumes after it — null when this was the last page. */
    public record Page(
            List<TraceV2Repository.Summary> rows, @Nullable String nextCursor) {}

    public static Key decode(@Nullable String cursor) {
        String token = PreviewCursor.decode(cursor).token();
        if (token == null) {
            return Key.NONE;
        }
        String[] parts = token.split(String.valueOf(SEP), -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return Key.NONE;
        }
        // Both keyset slots must actually carry a value. Arity and version alone are not enough: a token whose
        // started_at slot is empty decoded to a Key with a non-null-but-blank bound, the repository put it into
        // `started_at < ''::timestamptz`, and Postgres raised an invalid-input-syntax error that surfaced as a
        // JSON-RPC -32603. That breaks the promise this codec's own javadoc makes — an unreadable or stale
        // token restarts at the newest page — for a token that is exactly "unreadable". CasePageCodec already
        // guards its slots this way; these two now match it.
        if (parts[2].isEmpty() || parts[3].isEmpty()) {
            return Key.NONE;
        }
        return new Key(parts[1].isEmpty() ? null : parts[1], parts[2], parts[3]);
    }

    /**
     * Trim an over-fetched result to {@code pageSize} and mint the cursor for the next page.
     *
     * <p>The caller asked the repository for {@code pageSize + 1} rows. More than {@code pageSize} came
     * back, so there is another page and the cursor is seeded from the LAST ROW OF THIS PAGE (index
     * {@code pageSize - 1}), not from the extra row — the extra row is the first row of the next page and
     * seeding from it would skip it.
     */
    public static Page trim(List<TraceV2Repository.Summary> overFetched, int pageSize, @Nullable String sort) {
        if (overFetched.size() <= pageSize) {
            return new Page(overFetched, null);
        }
        TraceV2Repository.Summary last = overFetched.get(pageSize - 1);
        String cursor = PreviewCursor.encode(
                VERSION + SEP + sortValue(sort, last) + SEP + last.startedAt() + SEP + last.id(), 0);
        return new Page(List.copyOf(overFetched.subList(0, pageSize)), cursor);
    }

    /**
     * The shared clamp rule for a paged read: absent or nonsensical means the caller's default, and no
     * caller may ask for more than the caller's maximum.
     *
     * <p>The bounds are arguments rather than constants because the policy differs per surface and the rule
     * does not: REST pages a table and caps at 200, MCP pages into a model's context and caps at 100.
     */
    public static int clampLimit(@Nullable Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    /**
     * The sort value seeding the next page's cursor: the rollup column this page was ordered by, empty
     * when it has none.
     *
     * <p>An empty slot is not the number zero — it is "this trace had no value for the sort key", which is
     * what puts it in the NULLS LAST tail and is exactly what the next page's keyset has to be told.
     */
    private static String sortValue(@Nullable String sort, TraceV2Repository.Summary s) {
        if (sort == null) {
            return "";
        }
        // Each value is read into a local before the null test. Testing and dereferencing the accessor
        // separately reads as two independent calls that could disagree, which is exactly what a
        // nullability analyser reports — and here it would be right to, since the branch it flags is the
        // one that has to say "no value" rather than "zero".
        Long tokens = s.totalTokens();
        BigDecimal cost = s.totalCost();
        Long latency = s.latencyMs();
        return switch (sort.toLowerCase(Locale.ROOT)) {
            case TraceV2Repository.Sort.TOKENS -> tokens == null ? "" : tokens.toString();
            case TraceV2Repository.Sort.COST -> cost == null ? "" : cost.toPlainString();
            case TraceV2Repository.Sort.LATENCY -> latency == null ? "" : latency.toString();
            default -> "";
        };
    }
}

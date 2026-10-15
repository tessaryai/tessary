// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.ingest.PreviewCursor;
import ai.tessary.storage.TraceV2Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Keyset paging for the trace list, shared by REST and MCP {@code list_traces}: two encoders that disagree by one field
 * mis-resume silently. {@link #trim} does the over-fetch-by-one and mints the cursor in one step.
 */
public final class TracePageCodec {

    /**
     * Unit-separator control char: cannot collide with an ISO-8601 timestamp or a hex id. {@link SessionReadService}
     * uses the same character.
     */
    static final char SEP = '\u001f';

    /** An unversioned (v1) token is discarded and the reader gets page one rather than resuming off the v2 ordering. */
    private static final String VERSION = "v2";

    private TracePageCodec() {}

    public record Key(
            @Nullable String sortValue,
            @Nullable String startedAt,
            @Nullable String id) {
        public static final Key NONE = new Key(null, null, null);
    }

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
        // An empty slot would reach {@code started_at < ''::timestamptz} and raise in Postgres; restart at the newest
        // page instead, as CasePageCodec does.
        if (parts[2].isEmpty() || parts[3].isEmpty()) {
            return Key.NONE;
        }
        return new Key(parts[1].isEmpty() ? null : parts[1], parts[2], parts[3]);
    }

    /**
     * Trim a {@code pageSize + 1} result. The cursor is seeded from the last row of this page, not the extra row, which
     * is the next page's first.
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

    /** Absent or nonsensical means {@code defaultLimit}; never more than {@code maxLimit}. */
    public static int clampLimit(@Nullable Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    /** Empty, not zero, means no sort value, which places the trace in the NULLS LAST tail. */
    private static String sortValue(@Nullable String sort, TraceV2Repository.Summary s) {
        if (sort == null) {
            return "";
        }
        // Read into a local before the null test so the nullness checker sees one value.
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

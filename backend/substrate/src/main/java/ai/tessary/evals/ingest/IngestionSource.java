// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Per-provider pull adapter. Implementations paginate internally and return a
 * lazy Stream over {@link RawEntry}. Caller must close the stream.
 */
public interface IngestionSource extends AutoCloseable {

    String provider();

    Stream<RawEntry> fetch(ImportFilter filter);

    /**
     * Enumerate the selectable scopes of a given {@link ScopeKind} this source exposes —
     * the projects / datasets / saved views a user can pick to bound an on-demand selective pull.
     *
     * <p>Default: unsupported — returns an empty list — so existing adapters (Langfuse, Braintrust)
     * are not forced to implement discovery. A scope is a <em>convenience</em> for the selection UI;
     * a pull can always be bounded by the time-range / id-list {@link Selection} without it. Concrete
     * vendor connectors override this to call their list-projects / list-datasets endpoints.
     */
    default List<Scope> enumerateScopes(ScopeKind kind) {
        return List.of();
    }

    /**
     * Fetch only the spans belonging to a discovered saved view / dataset scope — the
     * <em>execution</em> half of the {@link #enumerateScopes(ScopeKind)} discovery seam. Given a
     * {@code savedView} handle (the {@link Scope#id()} of a {@link ScopeKind#SAVED_VIEW} /
     * {@link ScopeKind#DATASET}), the adapter resolves it to its member traces and streams exactly those
     * spans — narrowing happens <em>at the source</em>, not by a windowed scan + client-side filter, so a
     * dataset whose traces fall outside any time window is never silently dropped.
     *
     * <p>{@code context} carries the canonical window/attribute {@link ImportFilter} (e.g. an
     * {@code environment} the adapter forwards when re-fetching each member trace). The returned stream
     * is lazy; the caller must close it. An <b>empty</b> scope yields an empty stream — never a fallback
     * to the whole window.
     *
     * <p>Default: unsupported — {@code Stream.empty()} — so adapters without saved-view/dataset narrowing
     * (the contract every adapter starts from) are not forced to implement it. The pull runner only calls
     * this when a {@link Selection#savedView()} is set; otherwise it uses {@link #fetch(ImportFilter)}.
     */
    default Stream<RawEntry> fetchSavedView(String savedView, ImportFilter context) {
        return Stream.empty();
    }

    /** A page of spans plus the opaque cursor to resume from ({@code null} = exhausted). */
    record FetchPage(List<RawEntry> spans, @Nullable String nextCursor) {}

    /**
     * Resumable preview pull returning up to {@code limit} spans (one observation each),
     * starting after the position encoded in {@code cursor} ({@code null} = beginning).
     * The returned {@code nextCursor} is an opaque token to resume from, or null once
     * exhausted. The preview unit is a span, not a trace: rows map 1:1 to observations.
     *
     * <p>Default implementation does a single pass from the start, buffering a bounded
     * span prefix and slicing the requested window out of it; paging advances purely by
     * the cursor's skip count. The Langfuse/Braintrust adapters override it with
     * provider-native page-token paging (one upstream round trip per page).
     */
    default FetchPage fetchSpanPage(ImportFilter filter, @Nullable String cursor, int limit) {
        PreviewCursor c = PreviewCursor.decode(cursor);
        int skip = c.skip();
        List<RawEntry> buffer = new ArrayList<>();
        boolean more = false;
        try (Stream<RawEntry> s = fetch(filter)) {
            for (Iterator<RawEntry> it = s.iterator(); it.hasNext(); ) {
                if (buffer.size() >= skip + limit) {
                    more = true;
                    break;
                }
                buffer.add(it.next());
            }
        }
        int from = Math.min(skip, buffer.size());
        List<RawEntry> out = new ArrayList<>(buffer.subList(from, buffer.size()));
        String next = more ? PreviewCursor.encode(null, skip + limit) : null;
        return new FetchPage(out, next);
    }

    /**
     * Fetch a single observation by its source-native id, with full
     * (untruncated) input/output. Used by the preview detail view, which loads
     * one trace on demand rather than carrying every row's payload in the list.
     *
     * <p>{@code context} carries the same {@link ImportFilter} the list preview
     * used — providers that lack a fetch-by-id endpoint (e.g. Braintrust) reuse
     * it to re-scan the same window, and Braintrust still needs its projectId.
     */
    RawEntry fetchOne(String externalId, ImportFilter context);

    /**
     * Fetch every observation that belongs to one provider-native trace id. Trace
     * dataset items use this at run time, then the call site's grade mode decides
     * whether those spans are graded one-by-one or collapsed to one conversation unit.
     */
    default List<RawEntry> fetchTrace(String traceId, ImportFilter context) {
        List<RawEntry> out = new ArrayList<>();
        try (Stream<RawEntry> s = fetch(context)) {
            for (Iterator<RawEntry> it = s.iterator(); it.hasNext(); ) {
                RawEntry entry = it.next();
                if (traceId.equals(entry.traceId())) out.add(entry);
            }
        }
        return out;
    }

    /**
     * Targeted trace-selection by pre-resolved call site (tag-as-the-model). A source that stamps
     * {@code call_site_id} at ingest (the substrate / {@code sdk} source) returns the most recent observations
     * for that call site directly, so grader synthesis grounds by the tag instead of re-deriving the call site
     * through source mappings. Default empty: a pull source carries no stamped call site, so the caller falls
     * back to the mapping-based selection path.
     *
     * @param notBefore when non-null, only observations starting at or after this instant are eligible —
     *     used to bound grounding to recent activity (e.g. deterministic-grader codegen). {@code null} = no
     *     age bound (the original, unbounded "most recent N" behavior).
     */
    default List<RawEntry> fetchRecentByCallSite(String callSiteId, int limit, @Nullable Instant notBefore) {
        return List.of();
    }

    /**
     * Whether {@link #fetch} already returns full (untruncated) input/output, so
     * a bulk consumer can map directly from one paginated pass instead of calling
     * {@link #fetchOne} per entry. Default false (e.g. Langfuse's list endpoint
     * truncates I/O — use fetchOne by id). Providers whose fetch-by-id re-scans the
     * window (e.g. Braintrust) override to true to avoid O(N²) export.
     */
    default boolean returnsFullIoOnFetch() {
        return false;
    }

    @Override
    default void close() {
        /* no-op by default */
    }
}

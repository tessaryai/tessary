// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.ingest.PreviewCursor;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SessionRow;
import ai.tessary.storage.SpanKey;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.TraceV2Repository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Assembles the session read shapes: a keyset page of the project's sessions, and one session's detail —
 * identity, the totals summed from its traces' rollup columns, and those traces.
 *
 * <p><b>Why a service and not two controller methods.</b> Two surfaces render a session now — the REST
 * endpoint and the MCP {@code list_sessions} / {@code get_session} readers — and the detail is an assembly,
 * not a row: it composes a session row, a totals aggregate, and a capped trace list, and it carries two
 * facts that only mean something if they survive the assembly ({@code unsettled_traces} says how many of the
 * summed traces are still receiving spans, {@code traces_truncated} says the trace list is not all of them).
 * Assembled twice, the second copy is the one that drops a flag and reports a lower bound as a total.
 *
 * <p><b>Sessions carry no rollup, on purpose</b> (substrate-model.md §7.5). A trace goes quiet in seconds,
 * so a rollup can honestly settle it; a session may be resumed days later, so there is no gap of inactivity
 * that reliably means "finished". Totals are therefore summed at read time from already-materialized trace
 * rollup columns — an indexed read of up to {@link #SESSION_TRACE_CAP} rows for one session, never a scan
 * over spans — and the list has no sort parameter, because ordering sessions by cost or tokens would mean
 * summing every session in the project before the page could be chosen.
 *
 * <p><b>The list's {@code include=totals} totals are the same read, batched.</b> {@link #page} still never
 * sorts by an aggregate — it pages by {@code last_activity_at} exactly as before, then, only for the page
 * already chosen, sums each of those sessions' traces in one grouped query instead of one per row. Nothing
 * here lets a caller choose which sessions land on a page by their totals, only display them once recency has
 * — that is still the separate, undone piece of work the paragraph above describes.
 */
@Service
public class SessionReadService {

    /**
     * The most traces one session detail will sum. The spec's own cardinality note is "up to ~1,000
     * traces each", so this is the shape of the read rather than a guess: past it, the answer belongs to
     * a paged trace list, not to a totals card.
     */
    public static final int SESSION_TRACE_CAP = 1000;

    /**
     * The most spans one session's spans read will return. An order of magnitude past
     * {@link #SESSION_TRACE_CAP}: a session capped at 1,000 traces can still carry far more spans than
     * that, so this is a second, independent cap on the same read rather than a consequence of the first.
     */
    public static final int SESSION_SPAN_CAP = 5000;

    private final SessionRepository sessions;
    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final ToolCallRepository toolCalls;
    private final RetrievedDocRepository retrievalDocuments;

    public SessionReadService(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls,
            RetrievedDocRepository retrievalDocuments) {
        this.sessions = sessions;
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
        this.retrievalDocuments = retrievalDocuments;
    }

    /**
     * A page of the project's sessions, most recently active first.
     *
     * @param pageSize how many rows the caller wants — already clamped to that surface's policy
     *     ({@link TracePageCodec#clampLimit}). This method over-fetches one past it to detect a next page
     *     without a second {@code COUNT}.
     * @param cursor the {@code next_cursor} of a previous page. An unreadable or stale token silently
     *     restarts at the newest page rather than erroring: degrading to page one is the only failure mode a
     *     feed can absorb quietly.
     * @param includeTotals when true, batch-fetch each returned session's totals, dominant call site, and
     *     first-input/last-output preview (four grouped/DISTINCT ON queries for the whole page, never one per
     *     row) and carry them on each {@code SessionListItem}; when false (existing callers, including MCP),
     *     those fields stay null and no extra query runs.
     */
    public SessionDtos.SessionsPage page(
            String projectId, int pageSize, @Nullable String cursor, boolean includeTotals) {
        String beforeAt = null;
        String beforeId = null;
        String token = PreviewCursor.decode(cursor).token();
        if (token != null) {
            String[] parts = token.split(String.valueOf(TracePageCodec.SEP), -1);
            // Both slots must carry a value, not merely be present. An empty last_activity_at slot reached the
            // repository as a non-null blank bound and Postgres answered the resulting comparison with an
            // invalid-input-syntax error, which surfaced as a -32603 instead of the page one this method's own
            // javadoc promises for an unreadable token. Same hole TracePageCodec had; same guard.
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                beforeAt = parts[0];
                beforeId = parts[1];
            }
        }

        List<SessionRow> rows = sessions.listByProject(projectId, pageSize + 1, beforeAt, beforeId);
        String nextCursor = null;
        if (rows.size() > pageSize) {
            SessionRow last = rows.get(pageSize - 1);
            nextCursor = PreviewCursor.encode(last.lastActivityAt() + TracePageCodec.SEP + last.id(), 0);
            rows = rows.subList(0, pageSize);
        }

        Map<String, TraceV2Repository.SessionTotalsRow> totalsById = Map.of();
        Map<String, String> dominantCallSiteById = Map.of();
        Map<String, Integer> callSiteCountById = Map.of();
        Map<String, String> firstInputById = Map.of();
        Map<String, String> lastOutputById = Map.of();
        if (includeTotals && !rows.isEmpty()) {
            List<String> ids = rows.stream().map(SessionRow::id).toList();
            totalsById = traces.sessionTotalsForIds(projectId, ids);
            firstInputById = traces.firstInputPreviewForIds(projectId, ids);
            lastOutputById = traces.lastOutputPreviewForIds(projectId, ids);
            Map<String, List<TraceV2Repository.CallSiteCount>> bySession = new HashMap<>();
            for (TraceV2Repository.CallSiteCount c : traces.callSiteFrequencyForIds(projectId, ids)) {
                bySession.computeIfAbsent(c.sessionId(), k -> new ArrayList<>()).add(c);
            }
            dominantCallSiteById = new HashMap<>();
            callSiteCountById = new HashMap<>();
            for (var e : bySession.entrySet()) {
                TraceV2Repository.CallSiteCount top = e.getValue().stream()
                        .max(Comparator.<TraceV2Repository.CallSiteCount>comparingLong(
                                        TraceV2Repository.CallSiteCount::n)
                                .thenComparing(TraceV2Repository.CallSiteCount::lastSeenAt))
                        .orElseThrow();
                dominantCallSiteById.put(e.getKey(), top.callSiteId());
                callSiteCountById.put(e.getKey(), e.getValue().size());
            }
        }

        Map<String, TraceV2Repository.SessionTotalsRow> finalTotalsById = totalsById;
        Map<String, String> finalDominantCallSiteById = dominantCallSiteById;
        Map<String, Integer> finalCallSiteCountById = callSiteCountById;
        Map<String, String> finalFirstInputById = firstInputById;
        Map<String, String> finalLastOutputById = lastOutputById;
        return new SessionDtos.SessionsPage(
                rows.stream()
                        .map(s -> {
                            if (!includeTotals) {
                                return new SessionDtos.SessionListItem(
                                        s.id(),
                                        s.userId(),
                                        s.startedAt(),
                                        s.lastActivityAt(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null);
                            }
                            TraceV2Repository.SessionTotalsRow t = finalTotalsById.get(s.id());
                            return new SessionDtos.SessionListItem(
                                    s.id(),
                                    s.userId(),
                                    s.startedAt(),
                                    s.lastActivityAt(),
                                    t == null ? 0 : t.traceCount(),
                                    t == null ? 0 : t.unsettledTraces(),
                                    t == null ? null : t.spanCount(),
                                    t == null ? null : t.errorCount(),
                                    t == null ? null : t.totalTokens(),
                                    t == null ? null : t.totalCost(),
                                    t == null ? null : t.unpricedSpans(),
                                    finalDominantCallSiteById.get(s.id()),
                                    finalCallSiteCountById.getOrDefault(s.id(), 0),
                                    t == null ? null : t.inputTokens(),
                                    t == null ? null : t.outputTokens(),
                                    t == null ? null : t.cacheReadTokens(),
                                    t == null ? null : t.cacheWriteTokens(),
                                    t == null ? null : t.reasoningTokens(),
                                    t == null ? null : t.inputCost(),
                                    t == null ? null : t.outputCost(),
                                    finalFirstInputById.get(s.id()),
                                    finalLastOutputById.get(s.id()));
                        })
                        .toList(),
                nextCursor);
    }

    /**
     * One session: identity, the summed rollups of its traces, and those traces oldest first. Empty when the
     * project holds no such session — the project scoping IS the lookup, so a cross-tenant id is absent
     * rather than forbidden.
     */
    public Optional<SessionDtos.SessionDetail> detail(String projectId, String sessionId) {
        Optional<SessionRow> found = sessions.findById(projectId, sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionRow session = found.get();
        TraceV2Repository.SessionTotals totals = traces.sessionTotals(projectId, sessionId);
        // One over the cap, so "there are more than we summed" is a fact rather than a coincidence of
        // hitting the limit exactly.
        List<TraceV2Repository.Summary> rows = traces.listBySession(projectId, sessionId, SESSION_TRACE_CAP + 1);
        boolean truncated = rows.size() > SESSION_TRACE_CAP;
        if (truncated) {
            rows = rows.subList(0, SESSION_TRACE_CAP);
        }
        return Optional.of(new SessionDtos.SessionDetail(
                session.id(),
                session.userId(),
                session.startedAt(),
                session.lastActivityAt(),
                totals.traceCount(),
                totals.unsettledTraces(),
                totals.spanCount(),
                totals.errorCount(),
                totals.totalTokens(),
                totals.totalCost(),
                totals.unpricedSpans(),
                truncated,
                rows.stream().map(TraceDtos::item).toList()));
    }

    /**
     * Every span across a session's traces, assembled exactly like {@link TracesController}'s single-trace
     * read (same {@link TracesController#toSpan}, same payload/tool-call/retrieval-doc joins) but batched
     * across a trace-id set instead of one trace. Empty when the project holds no such session.
     *
     * <p>The trace-id set is the same capped, ordered read {@link #detail} builds {@code SessionDetail.traces}
     * from — not a separate {@code session_id} filter on {@code span} — so this response and a paired
     * {@code detail} call always describe the same traces. Side-table reads (payloads, tool calls, retrieval
     * documents) are narrowed further, to only the trace ids actually present in the (possibly
     * span-cap-truncated) spans read, so a trimmed trace's side-table rows are never fetched only to be
     * discarded.
     */
    public Optional<SessionDtos.SessionSpans> spans(String projectId, String sessionId) {
        if (sessions.findById(projectId, sessionId).isEmpty()) {
            return Optional.empty();
        }
        List<TraceV2Repository.Summary> traceRows = traces.listBySession(projectId, sessionId, SESSION_TRACE_CAP + 1);
        if (traceRows.size() > SESSION_TRACE_CAP) {
            traceRows = traceRows.subList(0, SESSION_TRACE_CAP);
        }
        List<String> traceIds =
                traceRows.stream().map(TraceV2Repository.Summary::id).toList();

        // One over the cap, so "there are more than we returned" is a fact rather than a coincidence of
        // hitting the limit exactly — same trick the single-trace read uses.
        List<SpanRow> spanRows = spans.listByTraceIds(projectId, traceIds, SESSION_SPAN_CAP + 1);
        boolean truncated = spanRows.size() > SESSION_SPAN_CAP;
        if (truncated) {
            spanRows = spanRows.subList(0, SESSION_SPAN_CAP);
        }

        Set<String> presentTraceIds = new LinkedHashSet<>();
        for (SpanRow s : spanRows) {
            presentTraceIds.add(s.traceId());
        }

        Map<String, SpanPayloadRow> payloadBySpan = new HashMap<>();
        List<SpanKey> keys =
                spanRows.stream().map(s -> new SpanKey(s.traceId(), s.id())).toList();
        for (SpanPayloadRow p : payloads.listByKeys(projectId, keys)) {
            payloadBySpan.put(p.spanId(), p);
        }
        Map<String, List<TracesController.ToolCallView>> toolsBySpan = new HashMap<>();
        for (ToolCallRepository.SpanToolCall t : toolCalls.listByTraceIds(projectId, presentTraceIds)) {
            if (t.spanId() != null) {
                toolsBySpan
                        .computeIfAbsent(t.spanId(), k -> new ArrayList<>())
                        .add(TracesController.toToolCall(t.row()));
            }
        }
        Map<String, List<TracesController.RetrievalDocumentView>> docsBySpan = new HashMap<>();
        for (RetrievedDocRepository.SpanRetrievedDoc d :
                retrievalDocuments.listByTraceIds(projectId, presentTraceIds)) {
            if (d.spanId() != null) {
                docsBySpan
                        .computeIfAbsent(d.spanId(), k -> new ArrayList<>())
                        .add(TracesController.toRetrievalDocument(d.row()));
            }
        }

        List<TracesController.SpanView> views = spanRows.stream()
                .map(s -> TracesController.toSpan(
                        s,
                        payloadBySpan.get(s.id()),
                        toolsBySpan.getOrDefault(s.id(), List.of()),
                        docsBySpan.getOrDefault(s.id(), List.of())))
                .toList();
        return Optional.of(new SessionDtos.SessionSpans(views, truncated));
    }
}

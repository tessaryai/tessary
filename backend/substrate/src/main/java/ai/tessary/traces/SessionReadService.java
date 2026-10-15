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
import ai.tessary.storage.TraceDetectionRepository;
import ai.tessary.storage.TraceFilters;
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
 * One assembly shared by REST and MCP, so {@code unsettled_traces} and {@code traces_truncated} cannot be dropped by a
 * second copy. Sessions carry no rollup (one may resume days later), so totals are summed at read time from trace
 * rollups, and the list cannot sort by an aggregate.
 */
@Service
public class SessionReadService {

    /**
     * The spec's own cardinality note is "up to ~1,000 traces each"; past it the answer belongs to a paged trace list.
     */
    public static final int SESSION_TRACE_CAP = 1000;

    /** An independent second cap: 1,000 traces can carry far more spans. */
    public static final int SESSION_SPAN_CAP = 5000;

    private final SessionRepository sessions;
    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final ToolCallRepository toolCalls;
    private final RetrievedDocRepository retrievalDocuments;
    private final TraceDetectionRepository detections;

    public SessionReadService(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls,
            RetrievedDocRepository retrievalDocuments,
            TraceDetectionRepository detections) {
        this.detections = detections;
        this.sessions = sessions;
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
        this.retrievalDocuments = retrievalDocuments;
    }

    /**
     * @param cursor an unreadable or stale token restarts at the newest page rather than erroring @param includeTotals
     * batched per page, never one query per row
     */
    public SessionDtos.SessionsPage page(
            String projectId,
            int pageSize,
            @Nullable String cursor,
            boolean includeTotals,
            TraceV2Repository.TraceQuery filter) {
        String beforeAt = null;
        String beforeId = null;
        String token = PreviewCursor.decode(cursor).token();
        if (token != null) {
            String[] parts = token.split(String.valueOf(TracePageCodec.SEP), -1);
            // Both slots must be non-empty: a blank bound reached Postgres as invalid input syntax instead of
            // restarting at page one.
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                beforeAt = parts[0];
                beforeId = parts[1];
            }
        }

        List<SessionRow> rows = sessions.listByProject(projectId, filter, pageSize + 1, beforeAt, beforeId);
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

        Map<String, List<TraceDtos.DetectionLabel>> labelsById = new HashMap<>();
        if (!rows.isEmpty()) {
            for (TraceDetectionRepository.SessionMark m : detections.forSessions(
                    projectId, rows.stream().map(SessionRow::id).toList())) {
                List<TraceDtos.DetectionLabel> labels =
                        labelsById.computeIfAbsent(m.sessionId(), k -> new ArrayList<>());
                TraceDtos.DetectionLabel label = new TraceDtos.DetectionLabel(m.classifierId(), m.classifierName());
                if (!labels.contains(label)) {
                    labels.add(label);
                }
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
                                        null,
                                        labelsById.getOrDefault(s.id(), List.of()));
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
                                    finalLastOutputById.get(s.id()),
                                    labelsById.getOrDefault(s.id(), List.of()));
                        })
                        .toList(),
                nextCursor);
    }

    /** Project scoping is the lookup, so a cross-tenant id is absent rather than forbidden. */
    public Optional<SessionDtos.SessionDetail> detail(
            String projectId, String sessionId, TraceV2Repository.TraceQuery filter) {
        Optional<SessionRow> found = sessions.findById(projectId, sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionRow session = found.get();
        TraceV2Repository.SessionTotals totals = traces.sessionTotals(projectId, sessionId);
        // One over the cap, so truncation is a fact rather than a coincidence of hitting the limit.
        List<TraceV2Repository.Summary> rows = traces.listBySession(projectId, sessionId, SESSION_TRACE_CAP + 1);
        boolean truncated = rows.size() > SESSION_TRACE_CAP;
        if (truncated) {
            rows = rows.subList(0, SESSION_TRACE_CAP);
        }
        List<TraceDetectionRepository.Mark> marks = detections.forTraces(
                projectId, rows.stream().map(TraceV2Repository.Summary::id).toList());
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
                TraceDtos.items(rows, marks),
                TraceDtos.marks(marks),
                TraceFilters.narrows(filter)
                        ? traces.idsInSessionMatching(projectId, sessionId, filter, SESSION_TRACE_CAP)
                        : null));
    }

    /** Top-level traces whatever their {@code thread_id}, without the sub-agent traces {@link #detail} carries. */
    public Optional<SessionDtos.ConversationDetail> conversation(String projectId, String conversationId) {
        List<TraceV2Repository.Summary> rows =
                traces.listByConversation(projectId, conversationId, SESSION_TRACE_CAP + 1);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        boolean truncated = rows.size() > SESSION_TRACE_CAP;
        if (truncated) {
            rows = rows.subList(0, SESSION_TRACE_CAP);
        }
        List<TraceDetectionRepository.Mark> marks = detections.forTraces(
                projectId, rows.stream().map(TraceV2Repository.Summary::id).toList());
        return Optional.of(new SessionDtos.ConversationDetail(conversationId, truncated, TraceDtos.items(rows, marks)));
    }

    /**
     * Uses the same capped trace-id read as {@link #detail}, so the two always describe the same traces. Side tables
     * are read only for traces present after the span cap.
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

        // One over the cap, so truncation is a fact rather than a coincidence of hitting the limit.
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

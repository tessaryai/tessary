// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.ToolCallRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.SessionTrace;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads the {@link SessionTimeline} of a session from the substrate: the session's traces latest end first, a page at
 * a time, each trace whole (its spans, payloads and tool calls), so the builder derives every trace on its own.
 */
@Service
public class SessionTimelineService {

    private static final Logger log = LoggerFactory.getLogger(SessionTimelineService.class);

    // A read's first page is one trace and each next page twice the last, up to this many, so a tail that stops early
    // has read at most about twice the traces it keeps.
    private static final int TRACES_PER_READ = 20;

    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final ToolCallRepository toolCalls;

    public SessionTimelineService(
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls) {
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
    }

    /** The whole timeline of the session. */
    public SessionTimeline load(String projectId, String sessionId) {
        return read(projectId, sessionId, Long.MAX_VALUE);
    }

    /**
     * {@code load(projectId, sessionId).tail(maxBytes)}, without reading the traces that cannot reach it: the same
     * events, with the same ids, sizes and data, numbered from 0. Reads whole traces latest end first and stops before a
     * trace when the events loaded after its end already weigh more than {@code maxBytes}: every event of that trace
     * and of the traces after it is at or before that end, so it sorts before the first event the tail leaves out. The
     * traces are read a page at a time, the first page one trace and each next page twice the last, so the spans of a
     * trace the tail leaves out are read only in the last page. The trace rows are read the same way, so a tail of a
     * session with many traces reads only its newest rows. A trace that is not settled has no end yet, since a span may
     * still arrive, so it is always read.
     *
     * <p>The one event a trace row does not bound: a user message whose carrying span never ended, in a settled trace,
     * which the builder places at the start of that span or of a span above it; when that start is later than the
     * trace's latest span end, a tail can miss it.
     */
    public SessionTimeline loadTail(String projectId, String sessionId, long maxBytes) {
        return read(projectId, sessionId, maxBytes);
    }

    /** The tail of at most {@code maxBytes}; the load line counts the events it returns, not the events it read. */
    private SessionTimeline read(String projectId, String sessionId, long maxBytes) {
        Instant start = Instant.now();
        SessionTraces source = new SessionTraces(traces, projectId, sessionId);
        List<SessionTimelineBuilder.Derived> derived = new ArrayList<>();
        long bytes = 0;
        int traceCount = 0;
        int spanCount = 0;
        boolean tracesRemain = false;
        pages:
        for (int pageSize = 1; ; pageSize = Math.min(2 * pageSize, TRACES_PER_READ)) {
            List<SessionTrace> page = source.next(pageSize);
            if (page.isEmpty()) break;
            if (enough(derived, bytes, maxBytes, page.get(0))) {
                tracesRemain = true;
                break;
            }
            Map<String, List<TimelineSpan>> byTrace = timelineSpans(projectId, page);
            for (SessionTrace trace : page) {
                if (enough(derived, bytes, maxBytes, trace)) {
                    tracesRemain = true;
                    break pages;
                }
                List<TimelineSpan> spansOfTrace = byTrace.getOrDefault(trace.id(), List.of());
                for (SessionTimelineBuilder.Derived d : SessionTimelineBuilder.derive(spansOfTrace, trace.settled())) {
                    derived.add(d);
                    bytes += d.event().bytes();
                }
                traceCount++;
                spanCount += spansOfTrace.size();
            }
        }
        SessionTimeline timeline = SessionTimelineBuilder.assemble(projectId, sessionId, derived, tracesRemain)
                .tail(maxBytes);
        long kept = timeline.bytes();
        StructuredLog.debug(log, Markers.OPS, "timeline.load")
                .message(
                        "built a timeline of %d events (%d bytes) from %d traces",
                        timeline.events().size(), kept, traceCount)
                .field("traces", traceCount)
                .field("spans", spanCount)
                .field("events", timeline.events().size())
                .field("bytes", kept)
                .field("truncated", timeline.truncated())
                .durationMs(start)
                .log();
        return timeline;
    }

    /**
     * A session's traces latest end first, a page at a time: the traces with no settled end in one read, then the ended
     * ones a page at a time after the last one read, so a tail that stops early never reads the older trace rows. A
     * trace that settles during the read could come back in an ended page; it is read once.
     */
    private static final class SessionTraces {
        private final TraceV2Repository traces;
        private final String projectId;
        private final String sessionId;
        private final ArrayDeque<SessionTrace> unended;
        private final Set<String> read = new HashSet<>();
        private @Nullable SessionTrace lastEnded;
        private boolean endedRemain = true;

        SessionTraces(TraceV2Repository traces, String projectId, String sessionId) {
            this.traces = traces;
            this.projectId = projectId;
            this.sessionId = sessionId;
            this.unended = new ArrayDeque<>(traces.listUnendedSessionTraces(projectId, sessionId));
        }

        /** Up to {@code n} traces not returned before; fewer only when none remain. */
        List<SessionTrace> next(int n) {
            List<SessionTrace> page = new ArrayList<>(n);
            while (page.size() < n && !unended.isEmpty()) {
                SessionTrace trace = unended.poll();
                if (read.add(trace.id())) page.add(trace);
            }
            while (page.size() < n && endedRemain) {
                int wanted = n - page.size();
                List<SessionTrace> ended = traces.listEndedSessionTraces(projectId, sessionId, lastEnded, wanted);
                endedRemain = ended.size() == wanted;
                for (SessionTrace trace : ended) {
                    lastEnded = trace;
                    if (read.add(trace.id())) page.add(trace);
                }
            }
            return page;
        }
    }

    /** Whether the events loaded after the trace's end already fill the tail, so the trace and the ones after it are not read. */
    private static boolean enough(
            List<SessionTimelineBuilder.Derived> derived, long bytes, long maxBytes, SessionTrace trace) {
        return bytes > maxBytes && bytesAfter(derived, trace.settledEnd()) > maxBytes;
    }

    /** The summed size of the events later than {@code end}; 0 for a trace with no end, so that trace is read. */
    private static long bytesAfter(List<SessionTimelineBuilder.Derived> derived, @Nullable String end) {
        if (end == null) return 0;
        Instant bound = Instant.parse(end);
        long sum = 0;
        for (SessionTimelineBuilder.Derived d : derived) {
            if (d.event().at().isAfter(bound)) sum += d.event().bytes();
        }
        return sum;
    }

    /** The spans of a page of traces, each with its payload and its first tool call, grouped by trace. */
    private Map<String, List<TimelineSpan>> timelineSpans(String projectId, List<SessionTrace> page) {
        if (page.isEmpty()) return Map.of();
        List<String> ids = page.stream().map(SessionTrace::id).toList();
        Map<String, SessionTrace> traceById = new HashMap<>();
        page.forEach(t -> traceById.put(t.id(), t));
        Map<List<String>, SpanPayloadRow> payloadByKey = new HashMap<>();
        for (SpanPayloadRow p : payloads.listByTraceIds(projectId, ids)) {
            payloadByKey.put(List.of(p.traceId(), p.spanId()), p);
        }
        Map<List<String>, ToolCallRow> toolByKey = new HashMap<>();
        for (ToolCallRepository.SpanToolCall call : toolCalls.listByTraceIds(projectId, ids)) {
            String traceId = call.row().traceId();
            String spanId = call.spanId();
            if (traceId != null && spanId != null) {
                toolByKey.putIfAbsent(List.of(traceId, spanId), call.row());
            }
        }
        Map<String, List<TimelineSpan>> byTrace = new HashMap<>();
        for (SpanRow span : spans.listByTraceIds(projectId, ids, Integer.MAX_VALUE)) {
            SessionTrace trace = traceById.get(span.traceId());
            if (span.isDeleted() || trace == null) continue;
            List<String> key = List.of(span.traceId(), span.id());
            byTrace.computeIfAbsent(span.traceId(), k -> new ArrayList<>())
                    .add(TimelineSpan.of(trace, span, payloadByKey.get(key), toolByKey.get(key)));
        }
        return byTrace;
    }
}

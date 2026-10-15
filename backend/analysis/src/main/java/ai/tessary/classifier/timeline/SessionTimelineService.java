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
import java.util.Queue;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads a session's traces latest end first, a page at a time, each trace whole, so the builder derives every trace on
 * its own.
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

    public SessionTimeline load(String projectId, String sessionId) {
        return read(projectId, sessionId, Long.MAX_VALUE, null);
    }

    /**
     * The same events as {@code load(...).tail(maxBytes)}, without reading traces that cannot reach the tail: stops
     * before a settled trace once the events after its end already exceed {@code maxBytes}. Unsettled traces are always
     * read. Known gap: a user message whose span never ended, placed later than its settled trace's latest span end,
     * can be missed.
     */
    public SessionTimeline loadTail(String projectId, String sessionId, long maxBytes) {
        return read(projectId, sessionId, maxBytes, null);
    }

    /**
     * Only events at or before {@code through}, then the tail, so a match ending at or before {@code through} is the
     * same match as on the whole timeline. Skips the spans of settled traces starting after {@code through}.
     */
    public SessionTimeline loadTailThrough(String projectId, String sessionId, long maxBytes, Instant through) {
        return read(projectId, sessionId, maxBytes, through);
    }

    /** The load line counts the events it returns, not the events it read. */
    private SessionTimeline read(String projectId, String sessionId, long maxBytes, @Nullable Instant through) {
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
            List<SessionTrace> reached =
                    page.stream().filter(trace -> reaches(trace, through)).toList();
            Map<String, List<TimelineSpan>> byTrace = timelineSpans(projectId, reached);
            for (SessionTrace trace : page) {
                if (enough(derived, bytes, maxBytes, trace)) {
                    tracesRemain = true;
                    break pages;
                }
                if (!reaches(trace, through)) continue;
                List<TimelineSpan> spansOfTrace = byTrace.getOrDefault(trace.id(), List.of());
                for (SessionTimelineBuilder.Derived d : SessionTimelineBuilder.derive(spansOfTrace, trace.settled())) {
                    if (through != null && d.event().at().isAfter(through)) continue;
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
     * Unsettled traces in one read, then ended ones a page at a time. A trace that settles during the read is read
     * once.
     */
    private static final class SessionTraces {
        private final TraceV2Repository traces;
        private final String projectId;
        private final String sessionId;
        private final Queue<SessionTrace> unended;
        private final Set<String> read = new HashSet<>();
        private @Nullable SessionTrace lastEnded;
        private boolean endedRemain = true;

        SessionTraces(TraceV2Repository traces, String projectId, String sessionId) {
            this.traces = traces;
            this.projectId = projectId;
            this.sessionId = sessionId;
            this.unended = new ArrayDeque<>(traces.listUnendedSessionTraces(projectId, sessionId));
        }

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

    /** An unsettled trace's start can still move earlier. */
    private static boolean reaches(SessionTrace trace, @Nullable Instant through) {
        return through == null
                || !trace.settled()
                || !Instant.parse(trace.startedAt()).isAfter(through);
    }

    private static boolean enough(
            List<SessionTimelineBuilder.Derived> derived, long bytes, long maxBytes, SessionTrace trace) {
        return bytes > maxBytes && bytesAfter(derived, trace.settledEnd()) > maxBytes;
    }

    /** 0 for a trace with no end, so that trace is read. */
    private static long bytesAfter(List<SessionTimelineBuilder.Derived> derived, @Nullable String end) {
        if (end == null) return 0;
        Instant bound = Instant.parse(end);
        long sum = 0;
        for (SessionTimelineBuilder.Derived d : derived) {
            if (d.event().at().isAfter(bound)) sum += d.event().bytes();
        }
        return sum;
    }

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

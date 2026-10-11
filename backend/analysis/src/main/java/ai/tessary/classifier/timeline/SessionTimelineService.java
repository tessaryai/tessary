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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * and of the traces after it is at or before that end, so it sorts before the first event the tail leaves out. A
     * trace that is not settled has no end yet, so it is always read.
     *
     * <p>The one event a trace row does not bound: a user message whose carrying span never ended, in a settled trace,
     * which the builder places at that span's start; when the start is later than the trace's latest span end, a tail
     * can miss it.
     */
    public SessionTimeline loadTail(String projectId, String sessionId, long maxBytes) {
        return read(projectId, sessionId, maxBytes).tail(maxBytes);
    }

    private SessionTimeline read(String projectId, String sessionId, long maxBytes) {
        Instant start = Instant.now();
        List<SessionTrace> all = traces.listSessionTraces(projectId, sessionId);
        List<SessionTimelineBuilder.Derived> derived = new ArrayList<>();
        long bytes = 0;
        int traceCount = 0;
        int spanCount = 0;
        boolean tracesRemain = false;
        pages:
        for (int from = 0; from < all.size(); from += TRACES_PER_READ) {
            List<SessionTrace> page = all.subList(from, Math.min(from + TRACES_PER_READ, all.size()));
            Map<String, List<TimelineSpan>> byTrace = timelineSpans(projectId, page);
            for (SessionTrace trace : page) {
                if (bytes > maxBytes && bytesAfter(derived, trace.settledEnd()) > maxBytes) {
                    tracesRemain = true;
                    break pages;
                }
                List<TimelineSpan> spansOfTrace = byTrace.getOrDefault(trace.id(), List.of());
                for (SessionTimelineBuilder.Derived d : SessionTimelineBuilder.derive(spansOfTrace)) {
                    derived.add(d);
                    bytes += d.event().bytes();
                }
                traceCount++;
                spanCount += spansOfTrace.size();
            }
        }
        SessionTimeline timeline = SessionTimelineBuilder.assemble(projectId, sessionId, derived, tracesRemain);
        StructuredLog.debug(log, Markers.OPS, "timeline.load")
                .message(
                        "built a timeline of %d events (%d bytes) from %d traces",
                        timeline.events().size(), bytes, traceCount)
                .field("traces", traceCount)
                .field("spans", spanCount)
                .field("events", timeline.events().size())
                .field("bytes", bytes)
                .field("truncated", tracesRemain)
                .durationMs(start)
                .log();
        return timeline;
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

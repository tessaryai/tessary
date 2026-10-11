// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads the {@link SessionTimeline} of a session from the substrate: the session's traces a page at a time, newest
 * first, each trace whole (its spans, payloads and tool calls), so the builder derives every trace on its own.
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
     * The newest events of the session whose summed size is at most {@code maxBytes}. Reads whole traces, newest
     * first, until their events reach the budget, so older traces are never read. Truncated when an event was cut or
     * a trace was left unread.
     */
    public SessionTimeline loadTail(String projectId, String sessionId, long maxBytes) {
        return read(projectId, sessionId, maxBytes).tail(maxBytes);
    }

    private SessionTimeline read(String projectId, String sessionId, long maxBytes) {
        Instant start = Instant.now();
        List<SessionTimelineBuilder.Derived> derived = new ArrayList<>();
        long bytes = 0;
        int traceCount = 0;
        int spanCount = 0;
        boolean tracesRemain = false;
        SessionTrace before = null;
        pages:
        while (true) {
            List<SessionTrace> page = traces.listSessionTraces(projectId, sessionId, before, TRACES_PER_READ);
            Map<String, List<TimelineSpan>> byTrace = timelineSpans(projectId, page);
            for (int i = 0; i < page.size(); i++) {
                List<TimelineSpan> trace = byTrace.getOrDefault(page.get(i).id(), List.of());
                for (SessionTimelineBuilder.Derived d : SessionTimelineBuilder.derive(trace)) {
                    derived.add(d);
                    bytes += d.event().bytes();
                }
                traceCount++;
                spanCount += trace.size();
                if (bytes >= maxBytes) {
                    tracesRemain = i < page.size() - 1 || page.size() == TRACES_PER_READ;
                    break pages;
                }
            }
            if (page.size() < TRACES_PER_READ) break;
            before = page.get(page.size() - 1);
        }
        SessionTimeline timeline = SessionTimelineBuilder.assemble(projectId, sessionId, derived, tracesRemain);
        StructuredLog.debug(log, "timeline.load")
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
